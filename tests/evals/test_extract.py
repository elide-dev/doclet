"""The reader sees only a question, page name, and retrieved documentation."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_extract import extract_answer


class ContractLookup(unittest.TestCase):
    def test_equivalent_serializations(self):
        question = "Which constant retains partial frames?"
        for excerpt in ["- BUFFER: Retain partial frames.", "BUFFER public static final Mode BUFFER Retain partial frames."]:
            with self.subTest(excerpt=excerpt):
                hit = extract_answer(question, excerpt, "Mode")
                self.assertEqual(hit.answer, "BUFFER")
                self.assertIn(hit.answer, hit.citation)
                self.assertIn(hit.citation, excerpt)

    def test_qualified_throws(self):
        excerpt = "- open(Path): Opens the file. Throws IOException if the disk is full"
        hit = extract_answer("When does Store.open fail?", excerpt, "Store")
        self.assertEqual(hit.answer, "if the disk is full")
        self.assertIn(hit.citation, excerpt)
        self.assertIsNone(extract_answer("When does Queue.open fail?", excerpt, "Store"))
        self.assertIsNone(extract_answer("When does Store.close fail?", excerpt, "Store"))

    def test_abstain_on_multiple_exceptions(self):
        question = "Which exception can a Store throw?"
        for excerpt in ["- open(): Throws IOException if full Throws TimeoutException if busy",
                        "open public void open () Throws: IOException - if full TimeoutException - if busy"]:
            self.assertIsNone(extract_answer(question, excerpt, "Store"))

    def test_return_description_is_verbatim(self):
        question = "What does Socket.timeout return?"
        excerpt = "timeout public int timeout () Returns: seconds before expiry."
        hit = extract_answer(question, excerpt, "Socket")
        self.assertEqual(hit.answer, "seconds before expiry")
        self.assertIn(hit.answer, hit.citation)

    def test_replacement_has_to_be_documented(self):
        question = "What replaces deprecated flush on Disk?"
        self.assertIsNone(extract_answer(question, "- flush(): Deprecated. Persists the write buffer.", "Disk"))
        self.assertEqual(extract_answer(question, "- flush(): Deprecated. Use sync().", "Disk").answer, "sync()")

    def test_different_condition_abstains(self):
        excerpt = "- open(Path): Throws: IOException - if the disk is full."
        self.assertIsNone(extract_answer("Which exception can Store.open throw if the network is down?",
                                         excerpt, "Store"))

    def test_explicit_fields_outweigh_summary_in_both_formats(self):
        for prefix in ("- clock():", "clock public long clock ()"):
            row = prefix + " Returns seconds. Returns: seconds since startup. Throws: Error - if stopped."
            hit = extract_answer("What does clock() return?", row, "Clock")
            self.assertEqual(hit.answer, "seconds since startup")
            self.assertIn(hit.citation, row)

    def test_does_not_answer_unsupported_question_shape(self):
        cases = [
            ("Which method never retries a failed request?", "- send(): Always retries a failed request."),
            ("Which encoding is supported for raw buffer data?", "- write(): UTF-8 is supported for raw buffer data."),
            ("Which method streams a response body?", "- body(): Returns body.\n- stream(): Streams a response body."),
            ("Why stream a response body?", "- stream(): Streams a response body."),
        ]
        for question, excerpt in cases:
            with self.subTest(question=question):
                self.assertIsNone(extract_answer(question, excerpt, "Client"))


if __name__ == "__main__":
    unittest.main()
