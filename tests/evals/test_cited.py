"""Synthetic documentation facts for the optional cited-answer reader."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

from eval_cited import extract_cited_answer


class CitedAnswerTests(unittest.TestCase):
    def assert_answer(self, question, excerpt, expected):
        result = extract_cited_answer(question, excerpt, "Reader")
        self.assertIsNotNone(result)
        self.assertEqual(result.answer, expected)
        self.assertIn(result.citation, excerpt)

    def test_explicit_replacement_in_two_formats(self):
        question = "What replaces deprecated Reader.load()?"
        for excerpt in [
            'load(): Deprecated. Use `read()`. Returns: the contents',
            'load @Deprecated public String load() Deprecated. Use read(). Returns the contents.',
        ]:
            self.assert_answer(question, excerpt, "read()")

    def test_explicit_deprecation_status(self):
        self.assert_answer("Is Reader.load() deprecated?",
                           "load @java.lang.Deprecated public String load()", "yes")
        self.assertIsNone(extract_cited_answer("Is Reader.load() deprecated?",
                                               "load(): Loads the current value.", "Reader"))

    def test_replacement_alternatives_abstain(self):
        question = "What replaces deprecated Reader.load()?"
        for excerpt in [
            "load(): Deprecated. Use read() or open().",
            "load(): Deprecated. Use read() if a file is available.",
            "load(): Deprecated. Use read(). Prefer open() for streams.",
            "load(): Calls deprecated implementations.",
            "write(): Deprecated. Use read().",
        ]:
            with self.subTest(excerpt=excerpt):
                self.assertIsNone(extract_cited_answer(question, excerpt, "Reader"))

    def test_named_default_and_declared_default(self):
        row = 'mode(): Default: "utf-8" Returns: the mode'
        self.assert_answer("What is the default value of Reader.mode()?", row, '"utf-8"')
        row = "load(String): Parameters: attempts - Defaults to 3. Parameters: cache - Defaults to false."
        self.assert_answer("What is the default for parameter attempts in Reader.load()?", row, "3")
        self.assert_answer("What is the default for parameter cache in Reader.load()?", row, "false")

    def test_default_qualified_missing_or_conflicting(self):
        for question, detail in [
            ("What is the default for parameter timeout in Reader.load()?", "Parameters: cache - Defaults to false."),
            ("What is the default for parameter cache in Reader.load()?", "Default: false"),
            ("What is the default for Reader.load() when offline?", "Default: false"),
            ("What is the default for Reader.load()?", "Default: false or true"),
            ("What is the default for Reader.load()?", "Default: false Default Value: true"),
            ("What is the default for Reader.load()?", "Default: false Default Value: true if configured"),
            ("What is the default for Writer.load()?", "Default: false"),
            ("What is the default for a parameter in Reader.load()?", "Default: false"),
        ]:
            with self.subTest(question=question, detail=detail):
                self.assertIsNone(extract_cited_answer(question, "load(): " + detail, "Reader"))

    def test_reverse_member_requires_concept_and_documented_detail(self):
        question = "Which Reader method enables compression for archived files?"
        for row in ["compress(): Compresses archived files.",
                    "compress public byte[] compress() Compresses archived files."]:
            self.assert_answer(question, row, "compress")
        for row in ["compress(): Compresses data.", "compress(): Reads archived files.",
                    "compress(): Decompresses archived files.",
                    "enableCompression(): Disables compression for archived files.",
                    "compress(): Never compresses archived files."]:
            with self.subTest(row=row):
                self.assertIsNone(extract_cited_answer(question, row, "Reader"))

    def test_reverse_member_excludes_other_owners_comparison_and_value_questions(self):
        row = "compress(): Compresses archived files."
        for question in [
            "Which Writer method compresses archived files?",
            "On Writer, which method compresses archived files?",
            "Which Reader method compresses archived files when offline?",
            "Which Reader method compresses archived files, compress or archive?",
            "Which Reader methods compress archived files?",
            "What return type does the method that compresses archived files use?",
            "Which parameter allows the method to compress archived files?",
        ]:
            with self.subTest(question=question):
                self.assertIsNone(extract_cited_answer(question, row, "Reader"))


if __name__ == "__main__":
    unittest.main()
