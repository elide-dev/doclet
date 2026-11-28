from hashlib import sha256
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "eval-compare.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("eval_compare", SCRIPT)
COMPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARE)


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)

    def record(self, filename, *, routes=("jdk", "api"), faster=True, **adjustments):
        rows = []
        for question in ("first?", "second?"):
            for route in routes:
                candidate = route == routes[1]
                rows.append(dict(route=route, question=question, correct=candidate or question == "first?",
                                 answer="supported", input_tokens=100 if candidate else 350,
                                 output_tokens=2 if candidate else 6, fetches=1 if candidate else 2,
                                 bytes=400 if candidate else 900,
                                 wall_ms=(5 if faster else 15) if candidate else 10))
        document = dict(routes=list(routes), model="gpt-6-luna", effort="low", excerpt_chars=600,
                        fixtures="fixture-integrity-hash", offline=False, results=rows)
        document.update(adjustments)
        path = self.folder / filename
        path.write_text(json.dumps(document))
        return path

    def test_paired_repeats_can_mix_one_axis_with_routes_changed(self):
        first = self.record("first.json")
        second = self.record("second.json", routes=("standard", "generated"), faster=False)
        result, ratios = COMPARE.compare([first, second], [("jdk", "api"), ("standard", "generated")], {})
        self.assertEqual(result["axes"]["wall_ms"], "mixed")
        self.assertTrue(all(ratio > 5 for ratio in ratios))
        self.assertEqual(result["axes"]["correct"], "win")
        self.assertEqual(result["runs"][0]["candidate"]["correct"], 2)
        self.assertEqual(result["runs"][0]["baseline"]["input_tokens"], 700)

    def test_reviews_preserve_frozen_grade_and_bind_filename_hash(self):
        first = self.record("first.json")
        digest = sha256(first.read_bytes()).hexdigest()
        review = dict(report=first.name, route="jdk", question="second?", correct=True,
                      reason="The answer and cited excerpt support the result.")
        result, _ = COMPARE.compare([first], [("jdk", "api")], {digest: [review]})
        self.assertEqual(result["runs"][0]["baseline"]["frozen_correct"], 1)
        self.assertEqual(result["runs"][0]["baseline"]["correct"], 2)
        self.assertEqual(result["axes"]["correct"], "tie")
        with self.assertRaisesRegex(ValueError, "checksum differs"):
            COMPARE.compare([first], [("jdk", "api")], {"0" * 64: [review]})
        with self.assertRaisesRegex(ValueError, "unknown"):
            COMPARE.compare([first], [("jdk", "api")], {digest: [dict(review, question="wrong?")]})

    def test_rejects_mismatched_policy_and_alignment(self):
        first = self.record("first.json")
        second = self.record("second.json", identifier_ids=True)
        with self.assertRaisesRegex(ValueError, "policy"):
            COMPARE.compare([first, second], [("jdk", "api")] * 2, {})
        source = json.loads(first.read_bytes())
        source["results"].pop()
        first.write_text(json.dumps(source))
        with self.assertRaisesRegex(ValueError, "alignment"):
            COMPARE.compare([first], [("jdk", "api")], {})

    def test_empirical_floor_uses_independent_runs(self):
        first = self.record("first.json")
        cmd = [sys.executable, str(SCRIPT), str(first), "--baseline", "jdk", "--candidate", "api"]
        self.assertEqual(subprocess.run([*cmd, "--require-all", "--min-efficiency", "5"],
                                       capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run([*cmd, "--min-efficiency", "10"],
                                       capture_output=True).returncode, 1)


    def test_fixture_requires_complete_question_coverage(self):
        fixture = self.folder / "questions.json"
        fixture.write_text(json.dumps([{"question": value} for value in ("first?", "second?", "third?")]))
        run = self.record("run.json", fixtures=sha256(fixture.read_bytes()).hexdigest())
        with self.assertRaisesRegex(ValueError, "exact frozen fixture"):
            COMPARE.compare([run], [("jdk", "api")], {}, fixture)
