"""Check native resume, usage accounting, and cleanup without an account or hosted calls."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_navigation_luna import LunaSession, MODEL


THREAD = "00112233-4455-6677-8899-aabbccddeeff"
OTHER = "00112233-4455-6677-8899-aabbccddeefe"


def response(input_tokens=200, cached=100, output=12, *, thread=THREAD, extra_items=(), commentary=()):
    events = [{"type": "thread.started", "thread_id": thread}, {"type": "turn.started"}]
    for kind in extra_items:
        events.append({"type": "item.completed", "item": {"type": kind}})
    for text in commentary:
        events.append({"type": "item.completed", "item": {"type": "agent_message", "text": text,
                                                           "phase": "commentary"}})
    events += [{"type": "item.completed", "item": {"type": "agent_message", "text": "read 2"}},
               {"type": "turn.completed", "usage": {"input_tokens": input_tokens,
                "cached_input_tokens": cached, "output_tokens": output}}]
    return subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")


class LunaSessionTests(unittest.TestCase):
    def test_one_new_prompt_per_native_turn_and_cumulative_token_deltas(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "ignored", "CODEX_API_KEY": "ignored"}), \
                patch("eval_navigation_luna.subprocess.run", side_effect=[
                    response(), response(420, 290, 17), subprocess.CompletedProcess([], 0)]) as run:
            with LunaSession("Use the document.") as reader:
                first = reader.turn("First document")
                second = reader.turn("Next page")
                self.assertEqual(reader.policy.read_text(), "Use the document.")
        create, resume, delete = run.call_args_list
        self.assertNotIn("resume", create.args[0])
        self.assertIn("resume", resume.args[0])
        self.assertEqual(resume.args[0][-2:], [THREAD, "-"])
        self.assertEqual(resume.kwargs["input"], "Next page")
        self.assertEqual(delete.args[0], ["codex", "delete", "--force", THREAD])
        self.assertFalse(Path(create.kwargs["cwd"]).exists())
        for call in [create, resume]:
            command = call.args[0]
            self.assertEqual(command[command.index("-m") + 1], MODEL)
            self.assertIn('model_reasoning_effort="low"', command)
            self.assertIn("--ignore-user-config", command)
            self.assertNotIn("OPENAI_API_KEY", call.kwargs["env"])
            self.assertNotIn("CODEX_API_KEY", call.kwargs["env"])
            self.assertNotIn("--last", command)
        for key, values in {"input_tokens": (200, 220), "cached_input_tokens": (100, 190),
                            "output_tokens": (12, 5)}.items():
            self.assertEqual((first[key], second[key]), values)

    def test_keeps_commentary_and_uses_only_final_visible_reply_as_command(self):
        with patch("eval_navigation_luna.subprocess.run", side_effect=[
                response(commentary=["Opening the index."]), subprocess.CompletedProcess([], 0)]):
            with LunaSession("Use supplied evidence") as reader:
                turn = reader.turn("Question")
        self.assertEqual(turn["answer"], "read 2")
        self.assertEqual(turn["commentary"], ["Opening the index."])
        self.assertEqual(turn["output_tokens"], 12)

    def test_empty_and_commentary_only_turns_remain_chargeable(self):
        for items, expected_commentary in (
                ([], []),
                ([{"type": "reasoning"}], []),
                ([{"type": "agent_message", "text": " "}], []),
                ([{"type": "agent_message", "phase": "commentary", "text": "Checking."}], ["Checking."])):
            with self.subTest(items=items):
                events = [json.loads(line) for line in response().stdout.splitlines()]
                events[2:-1] = [{"type": "item.completed", "item": item} for item in items]
                empty = subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")
                with patch("eval_navigation_luna.subprocess.run", side_effect=[
                        empty, response(420, 290, 17), subprocess.CompletedProcess([], 0)]):
                    with LunaSession("Use supplied evidence") as reader:
                        first = reader.turn("Question")
                        retry = reader.turn("Please enter a command")
                self.assertEqual(first["answer"], "")
                self.assertTrue(first["empty_reply"])
                self.assertEqual(first["commentary"], expected_commentary)
                self.assertEqual(first["input_tokens"], 200)
                self.assertEqual(first["output_tokens"], 12)
                self.assertEqual(retry["input_tokens"], 220)
                self.assertFalse(retry["empty_reply"])

    def test_missing_completion_or_usage_remains_an_error(self):
        for last in ([], [{"type": "turn.completed"}]):
            events = [json.loads(line) for line in response().stdout.splitlines()][:-1] + last
            result = subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")
            with patch("eval_navigation_luna.subprocess.run", side_effect=[
                    result, subprocess.CompletedProcess([], 0)]):
                with self.assertRaisesRegex(RuntimeError, "with usage"):
                    with LunaSession("Use supplied evidence") as reader:
                        reader.turn("Question")

    def test_tool_turn_is_charged_without_recording_any_of_its_content(self):
        events = [json.loads(line) for line in response(420, 290, 17, commentary=["private commentary"]).stdout.splitlines()]
        call = {"type": "mcp_tool_call", "arguments": {"value": "private argument"}, "result": "private result"}
        for phase in ("started", "updated", "completed"):
            events.insert(2, {"type": "item." + phase, "item": call})
        tool_turn = subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")
        with patch("eval_navigation_luna.subprocess.run", side_effect=[
                response(), tool_turn, subprocess.CompletedProcess([], 0)]) as run:
            with LunaSession("Use supplied evidence") as reader:
                reader.turn("First")
                result = reader.turn("Question")
        self.assertEqual(result["unsupported_item_types"], ["mcp_tool_call"])
        self.assertEqual((result["input_tokens"], result["cached_input_tokens"], result["output_tokens"]), (220, 190, 5))
        self.assertGreaterEqual(result["wall_ms"], 0)
        self.assertNotIn("private", json.dumps(result))
        for key in ("answer", "commentary", "prompt"):
            self.assertNotIn(key, result)
        self.assertEqual(run.call_args.args[0][-1], THREAD)

    def test_tool_turn_with_missing_provider_usage_aborts(self):
        events = [json.loads(line) for line in response(extra_items=["command_execution"]).stdout.splitlines()]
        del events[-1]["usage"]["output_tokens"]
        bad = subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")
        with patch("eval_navigation_luna.subprocess.run", side_effect=[bad, subprocess.CompletedProcess([], 0)]):
            with self.assertRaisesRegex(RuntimeError, "output_tokens"):
                with LunaSession("Use supplied evidence") as reader:
                    reader.turn("Question")

    def test_refuses_to_follow_or_remove_another_session(self):
        with patch("eval_navigation_luna.subprocess.run", side_effect=[
                response(), response(thread=OTHER), subprocess.CompletedProcess([], 0)]) as run:
            with self.assertRaisesRegex(RuntimeError, "different thread"):
                with LunaSession("Use supplied evidence") as reader:
                    reader.turn("First")
                    reader.turn("Second")
        self.assertEqual(run.call_args.args[0][-1], THREAD)

    def test_timed_out_first_turn_cleans_its_partial_thread(self):
        output = (json.dumps({"type": "thread.started", "thread_id": THREAD}) + "\n{").encode()
        with patch("eval_navigation_luna.subprocess.run", side_effect=[
                subprocess.TimeoutExpired([], 120, output=output), subprocess.CompletedProcess([], 0)]) as run:
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                with LunaSession("Use supplied evidence") as reader:
                    reader.turn("First")
        self.assertEqual(run.call_args.args[0][-1], THREAD)

    def test_rejects_counter_reset(self):
        with patch("eval_navigation_luna.subprocess.run", side_effect=[
                response(), response(input_tokens=50), subprocess.CompletedProcess([], 0)]):
            with self.assertRaisesRegex(RuntimeError, "cumulative"):
                with LunaSession("Use supplied evidence") as reader:
                    reader.turn("First")
                    reader.turn("Second")
