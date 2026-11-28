from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_extract import extract_contract_answer


class CompleteFields(unittest.TestCase):
    def test_return_includes_all_alternatives(self):
        value = "the left side; the right side on even iterations. Null for an empty stream."
        for prefix in ["pair public String pair () Returns whichever side is active.",
                       "- pair(): Returns whichever side is active."]:
            row = prefix + " Returns: " + value + " Throws: IOException - if the stream is closed."
            hit = extract_contract_answer("What does Switch.pair() return?", row, "Switch")
            self.assertEqual(hit.answer, value)
            self.assertIn(hit.citation, row)
            self.assertIn(hit.answer, hit.citation)

    def test_keep_qualified_symbols_in_field(self):
        text = "the enum value Mode.CUSTOM; otherwise Mode.DEFAULT"
        row = "- current(): Returns: " + text
        self.assertEqual(extract_contract_answer("What does current return?", row, "Options").answer, text)

    def test_match_exception_by_documented_condition_only(self):
        for row in ["reserve public void reserve () Throws: IOException - if stream is closed "
                    "LimitException - if count exceeds limit.",
                    "- reserve(): Throws: IOException - if stream is closed. "
                    "Throws: LimitException - if count exceeds limit."]:
            for condition, expected in [("the count exceeds the limit", "LimitException"),
                                        ("stream is closed", "IOException")]:
                hit = extract_contract_answer(f"Which exception does Store.reserve throw when {condition}?",
                                              row, "Store")
                self.assertEqual(hit.answer, expected)
                self.assertIn(hit.citation, row)
            self.assertIsNone(extract_contract_answer("Which exception does Store.reserve throw?", row, "Store"))
            self.assertIsNone(extract_contract_answer("Which exception does Store.reserve throw when limit exceeds count?",
                                                      row, "Store"))

    def test_implicit_return_question_requires_the_complete_field(self):
        question = "What queue capacity does Settings.defaults use?"
        base = "- defaults(): Builds the queue. Returns: a capacity of eight slots"
        hit = extract_contract_answer(question, base + ".", "Settings")
        self.assertEqual(hit.answer, "a capacity of eight slots.")
        self.assertIsNone(extract_contract_answer(question, base + "; zero for an offline queue.", "Settings"))
        self.assertIsNone(extract_contract_answer("What disk capacity does Settings.defaults use?",
                                                  base + ".", "Settings"))

    def test_failure_condition_requires_the_whole_clause(self):
        question = "When does Sink.flush fail?"
        base = "- flush(): Throws: IOException - when the queue is full"
        hit = extract_contract_answer(question, base + ".", "Sink")
        self.assertEqual(hit.answer, "when the queue is full.")
        self.assertIn(hit.answer, hit.citation)
        for suffix in ("; alternatively when paused.", ". It may also fail when shut down.",
                       ". Throws: OtherError - if blocked."):
            self.assertIsNone(extract_contract_answer(question, base + suffix, "Sink"))
        self.assertIsNone(extract_contract_answer("When does Network.flush fail?", base, "Sink"))

    def test_abstention(self):
        cases = [
            ("What does T.fetch return?", "- fetch(): Returns: row count; otherwise …"),
            ("What does T.fetch return?", "- fetch(): Returns: first. Returns: last."),
            ("What does Disk.fetch return?", "- fetch(): Returns: a handle."),
            ("What does T.open return?", "- fetch(): Returns: a handle."),
            ("What does T.fetch return when the disk is closed?", "- fetch(): Returns: bytes if open; null otherwise."),
            ("Which exception does T.fetch throw unless the disk is full?", "- fetch(): Throws: Error - if the disk is full."),
            ("When does T.fetch fail?", "- fetch(): Throws: Error - if x; otherwise if y."),
        ]
        for q, row in cases:
            with self.subTest(question=q, row=row):
                self.assertIsNone(extract_contract_answer(q, row, "T"))


if __name__ == "__main__":
    unittest.main()
