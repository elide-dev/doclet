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

    def test_contract_ranking_can_use_details_while_index_control_stays_frozen(self):
        from eval_discovery import choose_contract, enrich_jdk_members
        options = [Member("Calendar", "", "Calendar", "Maps timestamps."),
                   Member("Calendar", "epoch()", "epoch()", "Computes a date."),
                   Member("Calendar", "tick()", "tick()", "Computes a duration.")]
        html = """<section id="class-description">Calendar</section>
        <section class="detail" id="epoch()">epoch() has zero-based months.</section>
        <section class="detail" id="tick()">tick() includes leap seconds.</section>"""
        with tempfile.TemporaryDirectory() as directory:
            site = Path(directory)
            (site / "Calendar.html").write_text(html)
            enriched = enrich_jdk_members(site, options)
        self.assertEqual(options[2].summary, enriched[2].summary)
        self.assertEqual(options[2].search_text, "")
        self.assertIn("leap seconds", enriched[2].search_text)
        rank = lambda q, docs: sorted(docs, key=lambda key: "leap seconds" not in docs[key])
        self.assertEqual(choose_contract("Which method includes leap seconds?", enriched, rank), enriched[2])
        self.assertEqual(choose_member("Which method includes leap seconds?", enriched, rank), enriched[0])

    def test_explicit_overload_respects_type_names_arity_and_erasure(self):
        from eval_discovery import choose_contract
        methods = [Member("pkg/Queue", "write()", "write()", "preferred"),
                   Member("pkg/Queue", "write(java.util.List,a.Item)", "write(List<E>, Item)", ""),
                   Member("pkg/Queue", "write(java.util.List,b.Item)", "write(List<E>, Item)", ""),
                   Member("pkg/Queue", "write(java.lang.String...)", "write(String...)", "")]
        # The ranker favors the wrong overload whenever lookup fails to narrow the set.
        rank = lambda _, docs: sorted(docs)
        cases = [("How does Queue#write() behave?", 0),
                 ("When can Queue.write(List<Map<K,V>>, b.Item) fail?", 2),
                 ("What does pkg.Queue.write(String[]) return?", 3)]
        for question, index in cases:
            with self.subTest(question=question):
                self.assertEqual(choose_contract(question, methods, rank), methods[index])

    def test_method_name_in_scoped_prose_beats_incidental_description(self):
        from eval_discovery import choose_contract
        methods = [Member("pkg/Queue", "add()", "add()", "Implements purge."),
                   Member("pkg/Queue", "purge()", "purge()", ""),
                   Member("other/Stack", "purge()", "purge()", "")]
        rank = lambda _, docs: sorted(docs)
        self.assertEqual(choose_contract("When does purge on Queue return?", methods, rank), methods[1])
