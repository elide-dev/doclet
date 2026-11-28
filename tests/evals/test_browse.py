import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch


path = Path(__file__).resolve().parents[2] / "scripts/eval-browse.py"
spec = importlib.util.spec_from_file_location("browse", path)
browse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(browse)


class MockNavigation:
    def __init__(self):
        self.requests = [{"path": "index.html", "bytes": 200}]
        self.bytes = 200

    def start(self):
        return {"url": "index.html", "text": "Go to [L1] API index", "evidence_ids": []}

    def act(self, command):
        assert command == {"action": "open", "link": 1}
        self.bytes += 100
        self.requests.append({"path": "m.md", "bytes": 100})
        return {"url": "m.md", "text": "[E1] Probe.test(): the default is 2", "evidence_ids": [1]}

    def citation(self, i):
        assert i == 1
        return dict(page="Probe", path="pkg/Probe", anchor="test()", excerpt="the default is 2")


class MockSession:
    def __init__(self, replies):
        self.replies = iter(replies)
        self.prompts = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def turn(self, prompt):
        self.prompts.append(prompt)
        return dict(answer=json.dumps(next(self.replies)), input_tokens=100, cached_input_tokens=40,
                    output_tokens=5, wall_ms=1)


class BrowseTests(unittest.TestCase):
    case = {"question": "How many?", "page": "Probe", "anchors": ["test()"],
            "evidence": "default is 2", "answer": "^2$"}

    def trial(self, replies, steps=2):
        session = MockSession(replies)
        row = browse.run_question(self.case, MockNavigation(), lambda instructions: session, len, steps)
        return row, session

    def test_model_sees_no_fixture_or_future_pages_and_pays_each_turn(self):
        row, session = self.trial([{"action":"open", "link":1}, {"action":"answer", "answer":"2", "cite":[1]}])
        self.assertTrue(row["correct"])
        self.assertNotIn("Probe", session.prompts[0])
        self.assertNotIn("How many?", session.prompts[1])
        self.assertEqual(row["first_evidence_after_actions"], 1)
        self.assertEqual((row["model_turns"], row["fetches"], row["input_tokens"], row["bytes"]), (2, 2, 200, 300))
        self.assertEqual(browse.summarize([row])["uncached_input_tokens"], 120)

    def test_guess_and_unseen_id_get_no_grounding(self):
        row, _ = self.trial([{"action":"answer", "answer":"2", "cite":[]}])
        self.assertTrue(row["answer_match"])
        self.assertFalse(row["correct"])
        row, _ = self.trial([{"action":"answer", "answer":"2", "cite":[43]},
                             {"action":"answer", "answer":"unknown", "cite":[]}])
        self.assertEqual(row["stop"], "abstained")
        self.assertEqual(row["model_turns"], 2)

    def test_no_extra_action_after_budget(self):
        row, _ = self.trial([{"action":"open","link":1}], steps=0)
        self.assertEqual(row["stop"], "budget")
        self.assertEqual(row["fetches"], 1)

    def test_outside_tool_ends_the_trial_preserving_cost_and_prior_evidence(self):
        session = MockSession([])
        with patch.object(session, "turn", side_effect=[
                dict(answer='{"action":"open","link":1}', input_tokens=100, cached_input_tokens=40,
                     output_tokens=5, wall_ms=12),
                dict(unsupported_item_types=["mcp_tool_call"], input_tokens=200, cached_input_tokens=80,
                     output_tokens=10, wall_ms=29)]) as turn:
            row = browse.run_question(dict(self.case, answer=".*"), MockNavigation(), lambda _: session, len, 4)
        self.assertEqual(turn.call_count, 2)
        self.assertEqual((row["stop"], row["correct"], row["answer_match"]), ("out_of_browser_tool", False, False))
        self.assertEqual(row["citations"], [])
        self.assertEqual(row["first_evidence_after_actions"], 1)
        self.assertEqual(row["trace"][0]["command"], {"action": "open", "link": 1})
        last = row["trace"][1]
        self.assertEqual(last["observation"]["evidence_ids"], [1])
        self.assertEqual(last["unsupported_item_types"], ["mcp_tool_call"])
        self.assertEqual(last["wall_ms"], 29)
        for field in ("command", "reply", "commentary"):
            self.assertNotIn(field, last)
        metrics = browse.summarize([row])
        self.assertEqual((metrics["questions"], metrics["model_turns"], metrics["navigation_actions"]), (1, 2, 1))
        self.assertEqual((metrics["input_tokens"], metrics["cached_input_tokens"], metrics["output_tokens"]), (300, 120, 15))

    def test_first_emitted_valid_command_is_consistent_across_formats(self):
        result = {"commentary": ["Opening the index.", '{"action":"matches","query":"default"}'],
                  "answer": '{"action":"answer","answer":"unknown","cite":[]}'}
        self.assertEqual(browse.reader_command(result), ({"action":"matches","query":"default"}, "commentary"))

    def test_distinct_declarations_cannot_supply_joint_support(self):
        case = dict(self.case, anchors=["test()", "test(int)"], evidence="default is 2")
        a = dict(page="Probe", path="pkg/Probe", anchor="test()", excerpt="default")
        b = dict(a, anchor="test(int)", excerpt="is 2")
        self.assertFalse(browse.supports(case, [a, b]))
        self.assertTrue(browse.supports(case, [a, dict(b, anchor="test()")]))
