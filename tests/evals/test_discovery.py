"""Regression checks for matching declarations and retrieving their evidence."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_discovery import Member, choose_member, load_members, member_passage


class MemberLookupTest(unittest.TestCase):
    def test_index_parity_and_anchor_decoding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "index-all.html").write_text("""
                <dl class="index"><dt><a href="pkg/A.B.html#m(java.util.List)"
                class="member-name-link">m(List&lt;E&gt;)</a></dt><dd>Computes a sum.</dd>
                <dt><a href="pkg/package-summary.html">pkg</a></dt><dd>Package.</dd></dl>
            """)
            (root / "index-all.md").write_text(
                "- [`m(List<E>)`](pkg/A.B.md#m%28java.util.List%29), method in A.B: Computes a sum.\n"
                "- [Other](guides/setup.md), guide: Setup.\n")
            expected = [Member("pkg/A.B", "m(java.util.List)", "m(List<E>)", "Computes a sum.")]
            for fmt in ("html", "markdown"):
                self.assertEqual(load_members(root, fmt), expected)

    def test_nested_qualified_name_precedes_word_overlap(self):
        options = [Member("pkg/A", "read()", "read()", "Reads a value."),
                   Member("pkg/A.B", "open()", "open()", "Opens a value."),
                   Member("pkg/A.B", "read()", "read()", "Reads a value.")]
        selected = choose_member("What does A.B.read return?", options, lambda _, docs: sorted(docs))
        self.assertEqual(selected, options[2])

    def test_record_accessor_follows_only_its_component(self):
        html = """<section class="class-description"><dl><dd><span id="param-x">x</span> - hours</dd>
            <dd><span id="param-y">y</span> - days</dd></dl></section>
            <section class="detail" id="x()"><h3>x</h3>Returns the <a href="#param-x">x</a>.</section>"""
        member = Member("R", "x()", "x()", "")
        passage = member_passage(html, "html", member)
        self.assertIn("hours", passage)
        self.assertNotIn("days", passage)

    def test_overload_beyond_quick_summary_uses_full_section(self):
        md = """## API at a glance

- put(int): Integer only.

## Methods

### put(int)
An integer.

### put(String)
Text content.
"""
        member = Member("T", "put(java.lang.String)", "put(String)", "From index")
        self.assertIn("Text content.", member_passage(md, "markdown", member))

    def test_duplicate_display_signature_uses_disambiguated_description(self):
        md = """## Methods

### put(Item)
Writes package A.

### put(Item)
Writes package B.
"""
        member = Member("T", "put(b.Item)", "put(Item)", "Writes package B.")
        passage = member_passage(md, "markdown", member)
        self.assertIn("package B", passage)
        self.assertNotIn("package A", passage)
