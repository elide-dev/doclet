"""Synthetic declaration-intent tests for both formats' return-type metadata."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

from eval_discovery import Member, enrich_jdk_members
from eval_return_type import prefer_return_type, requested_return_type


def declaration(name, result):
    return Member("example/Reader", name + "()", name + "()", "", return_type=result)


class ReturnTypeTests(unittest.TestCase):
    def setUp(self):
        self.members = [declaration("readChars", "char[]"), declaration("readText", "java.lang.String"),
                        declaration("readBytes", "byte []"), declaration("unknown", "")]

    def assert_selected(self, question, name):
        matches = prefer_return_type(question, self.members)
        self.assertEqual([m.label for m in matches], [name + "()"])

    def test_reference_and_array_names(self):
        self.assert_selected("Which Reader method returns a String rather than bytes?", "readText")
        self.assert_selected("Which operation returns a Java java.lang.String?", "readText")
        self.assert_selected("Which Reader method returns bytes?", "readBytes")
        self.assert_selected("Which member returning a char array reads data?", "readChars")
        self.assert_selected("Which Reader method returns `char[]`?", "readChars")

    def test_primitive_and_wrapper_are_different(self):
        self.members = [declaration("size", "int"), declaration("longSize", "long"),
                        declaration("boxedSize", "java.lang.Integer"),
                        declaration("hasData", "boolean"), declaration("boxedFlag", "java.lang.Boolean")]
        self.assert_selected("Which method returns an int?", "size")
        self.assert_selected("Which method returns long?", "longSize")
        self.assert_selected("Which method returns an Integer?", "boxedSize")
        self.assert_selected("Which method returns a boolean?", "hasData")

    def test_natural_output_heads_ignore_adjectives_and_excluded_contrasts(self):
        for question in [
            "Which method returns a normalized UTF-8 string instead of returning bytes?",
            "Which method returns a compact ASCII string without line breaks or tabs, rather than returning bytes?",
            "Which method returns a cleaned UTF-8 string?",
            "Which method returns a normalized UTF-8 String?",
            "Which method returns a normalized `java.lang.String`?",
        ]:
            with self.subTest(question=question):
                self.assert_selected(question, "readText")
        self.assertEqual(requested_return_type("Which method returns a signed integer?"), "int")
        self.assertEqual(requested_return_type("Which method returns a normalized boolean?"), "boolean")
        for question in ["Which method returns valid UTF-8?", "Which method returns a URL-safe identifier?",
                         "Which method returns a normalized UTF-8 string or bytes?",
                         "Which method returns an ASCII string, or returns bytes?",
                         "Which method returns a char array, or bytes?",
                         "Which method does not return a normalized string?"]:
            with self.subTest(question=question):
                self.assertIsNone(requested_return_type(question))

    def test_ambiguous_or_absent_constraints(self):
        self.assert_selected("Which method returns an InputStream?", "unknown")
        for question in ["Which method reads the stream?", "Which method returns String or bytes?",
                         "What does Reader.read() return?", "Which method returns an object?",
                         "Which method returns text?", "Which method returns List<String>?"]:
            with self.subTest(question=question):
                self.assertIsNone(requested_return_type(question))
                self.assertEqual(prefer_return_type(question, self.members), self.members)
        members = self.members[:-1]
        self.assertEqual(prefer_return_type("Which method returns an InputStream?", members), [])
        matches = [declaration("left", "a.Result"), declaration("right", "b.Result")]
        self.assertEqual(prefer_return_type("Which method returns a Result?", matches), matches)
        generic = [declaration("left", "java.util.List<java.util.List<String>>"),
                   declaration("right", "java.util.List<String>[]")]
        self.assertEqual(prefer_return_type("Which method returns a List?", generic), generic[:1])

    def test_structured_javadoc_return_types(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page = root / "example/Reader.html"
            page.parent.mkdir()
            page.write_text("""
<section class="detail" id="readChars()">
  <h3>readChars</h3><div class="member-signature"><span class="return-type">char[]</span></div>
</section>
<section class="detail" id="readText()">
  <h3>readText</h3><div class="member-signature">
    <span class="return-type"><a title="class in java.lang">String</a></span>
  </div>
</section>
<section class="detail" id="readBytes()">
  <h3>readBytes</h3><div class="member-signature"><span class="return-type">byte[]</span></div>
</section>""")
            bare = [Member(m.path, m.anchor, m.label, m.summary) for m in self.members]
            enriched = enrich_jdk_members(root, bare, include_return_types=True)
            self.assertEqual([m.return_type for m in enriched], ["char[]", "java.lang.String", "byte[]", ""])
            question = "Which method returns char[]?"
            self.assertEqual(prefer_return_type(question, enriched)[0].label, "readChars()")


if __name__ == "__main__":
    unittest.main()
