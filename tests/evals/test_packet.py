"""Synthetic framing and reply validation for one-turn documentation packets."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_packet import (identifier_prompt, pack_candidates, packet_prompt, parse_identifier_reply,
                         parse_packet_reply)


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

    def test_identifier_protocol_maps_a_bare_number_to_its_source(self):
        packet = pack_candidates([
            member("write(java.lang.String)", "Writes a string."),
            member("writeBytes(byte[])", "Writes bytes."),
        ])
        self.assertIn("Reply 0", identifier_prompt("Which Writer method writes bytes?", packet))
        self.assertEqual(parse_identifier_reply("2\n", packet), (packet[1], "writeBytes"))
        for reply in ["0", "3", "01", "+1", "[1]", "1 2", "1\n2", "1 write"]:
            self.assertEqual(parse_identifier_reply(reply, packet), (None, "unknown"))

    def test_identifier_protocol_handles_rank_one_singleton_and_excludes_value_queries(self):
        only = member("LOWEST", "The lowest setting.")
        self.assertIn("[1]", identifier_prompt("On Writer, which constant denotes the lowest setting?", [only]))
        self.assertEqual(parse_identifier_reply("1", [only]), (only, "LOWEST"))
        self.assertNotIn("id", only)
        for question in [
            "Which method argument controls buffering?",
            "Which member's value should I pass?",
            "Which method argument or exception is documented?",
            "Which exception does this method throw?",
            "Which value does this function produce?",
            "Which Writer method return value signals failure?",
            "What does Writer.write return?",
        ]:
            with self.subTest(question=question):
                self.assertIsNone(identifier_prompt(question, [only]))
        for anchor in ["", "description-heading", "<init>()"]:
            self.assertIsNone(identifier_prompt("Which method writes bytes?", [member(anchor, "Writes bytes.")]))


if __name__ == "__main__":
    unittest.main()
