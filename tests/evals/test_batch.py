import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("eval_batch", SCRIPTS / "eval-batch.py")
BATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BATCH)


class ReplayTest(unittest.TestCase):
    def setup_rows(self):
        cases = [
            dict(question="Which declaration returns the copied value?", page="Buffer", anchor="copy()",
                 evidence="copies", answer="copy", answer_all=["buffer"]),
            dict(question="What does Priority.read return?", page="Priority", anchor="read()",
                 evidence="priority", answer="priority"),
        ]
        candidates = [
            dict(id=1, page="Buffer", path="p/Buffer", anchor="move()", excerpt="move(): copies state."),
            dict(id=2, page="Buffer", path="p/Buffer", anchor="copy()", excerpt="copy(): copies the buffer."),
        ]
        rows = [
            # First-turn scores and selection are deliberately different from the replay.
            dict(question=cases[0]["question"], packet="[1] move(): copies state.\n[2] copy(): copies the buffer.",
                 candidates=candidates, page="Buffer", anchor="move()", evidence_hit=False,
                 excerpt=candidates[0]["excerpt"], extraction=None),
            dict(question=cases[1]["question"], page="Priority", anchor="read()", path="p/Priority",
                 excerpt="read(): Returns: priority.", extraction={"answer": "priority"}),
        ]
        return cases, rows

    def test_multi_grades_fresh_anchor_and_retains_exact_packet(self):
        cases, rows = self.setup_rows()
        with patch.object(BATCH, "read_answer", return_value={
                "answer": '{"0":"[2] copy the buffer"}', "input_tokens": 20, "output_tokens": 5}) as reader:
            audit, usage, _, fallbacks = BATCH.replay_rows(rows, cases, "gpt-6-luna")
        self.assertEqual(fallbacks, 1)
        self.assertEqual(usage["input_tokens"], 20)
        self.assertEqual([r["correct"] for r in audit], [True, True])
        self.assertEqual(audit[0]["citation"]["anchor"], "copy()")
        self.assertEqual(audit[0]["packet"], rows[0]["packet"])
        self.assertEqual(audit[0]["candidates"], rows[0]["candidates"])
        tasks = json.loads(reader.call_args.args[2].split("\n", 1)[1])
        self.assertEqual(tasks, {"0": {"question": cases[0]["question"], "document": rows[0]["packet"], "cite": True}})
        self.assertEqual(reader.call_args.kwargs["output_schema"]["required"], ["0"])

    def test_wrong_anchor_missing_required_answer_and_invalid_citation_all_fail(self):
        cases, rows = self.setup_rows()
        for reply in ["[1] copy the buffer", "[2] copy", "[7] copy the buffer", "unknown"]:
            with self.subTest(reply=reply), patch.object(BATCH, "read_answer", return_value={
                    "answer": json.dumps({"0": reply}), "input_tokens": 14, "output_tokens": 2}):
                audit, *_ = BATCH.replay_rows(rows, cases, "gpt-6-luna")
                self.assertFalse(audit[0]["correct"])

    def test_tampered_packet_is_rejected_before_hosted_turn(self):
        cases, rows = self.setup_rows()
        rows[0]["packet"] += "\n[3] different excerpt"
        with patch.object(BATCH, "read_answer") as reader:
            with self.assertRaisesRegex(ValueError, "preserve the recorded"):
                BATCH.replay_rows(rows, cases, "gpt-6-luna")
        reader.assert_not_called()

    def test_singletons_keep_local_answers_and_abstention(self):
        cases, rows = self.setup_rows()
        rows[0].update(candidates=[], page=None, excerpt="", extraction=None)
        with patch.object(BATCH, "read_answer") as reader:
            audit, _, _, fallbacks = BATCH.replay_rows(rows, cases, "gpt-6-luna")
        self.assertEqual(fallbacks, 0)
        self.assertEqual([r["answer"] for r in audit], ["unknown", "priority"])
        self.assertEqual([r["correct"] for r in audit], [False, True])
        reader.assert_not_called()
