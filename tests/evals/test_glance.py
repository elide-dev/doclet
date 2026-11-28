"""The local query experiments read linked and legacy Markdown contract summaries."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_discovery import Member, member_passage
from eval_passages import passage
from eval_rank import rank


class GlanceRowsTest(unittest.TestCase):
    page = """## API at a glance

- [accept(Item[])](#accept%28a.Item%5B%5D%29): First package.
- [`accept(Item[])`](#accept%28b.Item%5B%5D%29): Other package.
- [`Queue(Item[])`](#%3Cinit%3E%28b.Item%5B%5D%29): Creates an empty queue.
- limit: Maximum queue size.

## Methods

### accept(Item[])
<a id="accept(a.Item[])"></a>

First details.

### accept(Item[])
<a id="accept(c.Item[])"></a>

Third details.
"""

    def test_exact_decoded_anchor_precedes_display_or_repeated_headings(self):
        for anchor, label, expected in (
                ("accept(a.Item[])", "accept(Item[])", "accept(Item[]): First package."),
                ("accept(b.Item[])", "accept(Item[])", "accept(Item[]): Other package."),
                ("<init>(b.Item[])", "Queue(b.Item[])", "Queue(Item[]): Creates an empty queue.")):
            with self.subTest(anchor=anchor):
                member = Member("Queue", anchor, label, "Index description.")
                self.assertEqual(member_passage(self.page, "markdown", member), "- " + expected)

    def test_unlisted_overload_and_legacy_component(self):
        member = Member("Queue", "accept(c.Item[])", "accept(Item[])", "")
        self.assertIn("Third details.", member_passage(self.page, "markdown", member))
        missing = Member("Queue", "accept(d.Item[])", "accept(Item[])", "Index contract.")
        self.assertEqual(member_passage(self.page, "markdown", missing), "accept(Item[]): Index contract.")
        component = Member("Queue", "limit", "limit", "")
        self.assertEqual(member_passage(self.page, "markdown", component), "- limit: Maximum queue size.")

    def test_passage_ranker_and_layouts_read_visible_text(self):
        def rank_visible(question, entries):
            for text in entries.values():
                self.assertNotIn("](#", text)
            return rank(question, entries)

        for question, layout, expected in (
                ("Does Queue.accept(b.Item[]) use the first package?", "table",
                 "|Member|Contract|\n|---|---|\n|accept(Item[])|Other package.|"),
                ("What does Queue(b.Item[]) create?", "symbols", "- Queue(Item[]): Creates an empty queue."),
                ("What is Queue.limit on the first package?", "bullets", "- limit: Maximum queue size.")):
            with self.subTest(layout=layout):
                self.assertEqual(passage(self.page, "markdown", layout, question, "Queue", rank_visible, 1),
                                 expected)

    def test_unlinked_display_collisions_still_use_the_index_description(self):
        legacy = """## API at a glance

- get(Item): First package.
- get(Item): Other package.

## Methods

### get(Item)
First.

### get(Item)
Other.
"""
        member = Member("Queue", "get(b.Item)", "get(Item)", "More specific index contract.")
        self.assertEqual(member_passage(legacy, "markdown", member), "get(Item): More specific index contract.")
