"""Discovery cost accounting and paired archive validation."""
import copy
import importlib.util
from pathlib import Path
import sys
import unittest


path = Path(__file__).resolve().parents[2] / "scripts/report-browse.py"
sys.path.insert(0, str(path.parent))
spec = importlib.util.spec_from_file_location("report_browse", path)
report_browse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report_browse)


def row(case, arm, first, correct, visible):
    trace = []
    for i, tokens in enumerate(visible):
        command = {"action": "find", "query": "x|y"} if i < len(visible) - 1 else {"action": "answer"}
        trace.append(dict(input_tokens=20, cached_input_tokens=10, output_tokens=2,
                          observation_tokens=tokens, observation={"url": "index.html"}, command=command))
    n = len(visible)
    return dict(case=case, site=arm, question=f"Question {case}", trace=trace, model_turns=n,
                correct=correct, first_evidence_after_actions=first, observation_tokens=sum(visible),
                input_tokens=20*n, cached_input_tokens=10*n, output_tokens=2*n, stop="answered",
                requests=[{"path": "index.html", "bytes": 90}], bytes=90, fetches=1, wall_ms=100)


def archive(*rows):
    return {"protocol": "model-directed HTML navigation", "cases": [1, 2, 3],
            "order": ["JDK", "generated"], "results": list(rows)}


class ReportTests(unittest.TestCase):
    def test_charge_hit_through_first_evidence_and_entire_observed_miss(self):
        solved = row(1, "JDK", 1, True, [5, 7, 9])
        missed = row(2, "JDK", None, False, [2, 3])
        guessed = row(3, "JDK", 0, False, [4, 8])
        stats = report_browse.trial_metrics([solved, missed, guessed])
        self.assertEqual(stats["charged_visible"], 12 + 5 + 4)
        self.assertEqual(stats["observation_tokens"], 21 + 5 + 12)
        self.assertEqual(stats["reached"], 2)
        self.assertEqual(stats["grounded"], 1)
        self.assertEqual(stats["mean_grounded"], 1)
        self.assertEqual(stats["mean_reached"], 0.5)
        self.assertAlmostEqual(stats["mean_charged"], 2 / 3)
        self.assertEqual((stats["input_tokens"], stats["cached_input_tokens"], stats["output_tokens"]),
                         (140, 70, 14))
        self.assertEqual(stats["uncached_input_tokens"], 70)
        self.assertAlmostEqual(stats["answers_per_100k"], 100_000 / 154)

    def test_partial_uses_only_completed_pairs_and_escapes_trace_queries(self):
        a = row(1, "JDK", 1, True, [5, 7])
        b = row(1, "generated", None, False, [2])
        unpaired = row(2, "generated", 0, True, [999])
        report = archive(a, unpaired, b)
        with self.assertRaisesRegex(ValueError, "1/3"):
            report_browse.markdown(report)
        result = report_browse.markdown(report, partial=True, traces=True)
        self.assertIn("| Visible tokens to evidence, misses charged | 12 | 2 |", result)
        self.assertIn("| Mean actions to evidence, reached cases | 1.00 | — |", result)
        self.assertIn('find "x\\|y"', result)
        self.assertNotIn("999", result)
        self.assertIn("No case received a grounded citation from every arm", result)

    def test_paired_successes_exclude_costs_of_early_abstentions(self):
        a = row(1, "JDK", 1, True, [5, 7])
        b = row(1, "generated", 0, True, [8])
        report = archive(a, b, row(2, "JDK", None, False, [3]), row(2, "generated", 0, True, [90]))
        result = report_browse.markdown(report, partial=True)
        self.assertIn("grounded citation (1)", result)
        self.assertIn("| generated | 1 | 8 | 20 | 2 | 0.10 |", result)

    def test_review_preserves_frozen_scores_and_matches_costs_on_reviewed_cases(self):
        a = row(1, "JDK", None, False, [5, 7])
        b = row(1, "generated", 0, True, [8])
        result = report_browse.markdown(archive(a, b), partial=True, traces=True,
                                        corrections={("JDK", a["question"]): {"correct": True}})
        self.assertIn("| Grounded answers | 0 | 1 |", result)
        self.assertIn("| Grounded answers after citation review | 1 | 1 |", result)
        self.assertIn("grounded citation (1)", result)
        self.assertIn("reviewed pass", result)

    def test_rejects_duplicates_mismatches_and_invalid_observation_boundaries(self):
        a = row(1, "JDK", 1, True, [5, 7])
        b = row(1, "generated", 1, True, [5, 7])
        for field, value in (("question", "different"), ("input_tokens", 1000), ("cached_input_tokens", 123),
                            ("first_evidence_after_actions", 2), ("model_turns", 0), ("bytes", 89)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                report_browse.paired_trials(archive(a, dict(b, **{field: value})), partial=True)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            report_browse.paired_trials(archive(a, copy.deepcopy(a)), partial=True)
