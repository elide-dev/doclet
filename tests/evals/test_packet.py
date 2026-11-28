"""Synthetic framing and reply validation for one-turn documentation packets."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_packet import pack_candidates, packet_prompt, parse_packet_reply


def member(anchor, excerpt):
    return dict(page="Writer", path="example/Writer", anchor=anchor, excerpt=excerpt)


class CitationPacketTests(unittest.TestCase):
    def test_budget_counts_labels_and_preserves_complete_fields(self):
        items = [member("a()", "Returns: first."), member("b()", "Returns: second."),
                 member("c()", "Returns: final.")]
        joined = "[1] Returns: first.\n[2] Returns: second."
        packet = pack_candidates(items, len(joined))
        self.assertEqual([m["id"] for m in packet], [1, 2])
        self.assertEqual([m["anchor"] for m in packet], ["a()", "b()"])
        self.assertTrue(packet_prompt("What does it return?", packet).endswith(joined))
        self.assertNotIn("id", items[0])

    def test_skips_oversized_empty_and_duplicate_members(self):
        packet = pack_candidates([
            member("a()", "Returns: " + "x" * 600),
            member("b()", "  "),
            member("c()", "Returns: one."),
            member("c()", "Returns: second redundant copy."),
            member("d()", "Returns: two."),
        ], 40)
        self.assertEqual([m["anchor"] for m in packet], ["c()", "d()"])
        self.assertEqual([m["id"] for m in packet], [1, 2])

    def test_resolves_only_one_selected_exact_citation(self):
        packet = pack_candidates([member("a()", "Returns: one."), member("b()", "Returns: two.")])
        self.assertEqual(parse_packet_reply(" [2] two \n", packet), (packet[1], "two"))
        for invalid in ["[0] zero", "[3] three", "[01] one", "[+1] one",
                        "[1][2] one", "[1] one [2]", "[1] one [1]",
                        "[1] one [-2]", "[1] one [ +2 ]",
                        "[1] one\n[2] two", "[1] ", "[1] unknown.", "unknown",
                        "maybe [1] one", "[1] one\nMore explanation."]:
            with self.subTest(reply=invalid):
                self.assertEqual(parse_packet_reply(invalid, packet), (None, "unknown"))


if __name__ == "__main__":
    unittest.main()
