from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_literal import extract_literal_answer


class LiteralAnswerTest(unittest.TestCase):
    def test_null_is_cited_in_both_layouts(self):
        for row in ["- find(Key): Returns: the value found. null if no such entry exists.",
                    "find public Value find (Key key) Returns: the value found. null if no such entry exists."]:
            hit = extract_literal_answer("What does Cache.find return if the entry does not exist?",
                                         row, "Cache")
            self.assertEqual(hit.answer, "null")
            self.assertEqual(hit.citation, "null if no such entry exists.")
            self.assertIn(hit.citation, row)

    def test_conditional_values_keep_their_citations(self):
        cases = [
            ("true if the socket closes and false otherwise.", "true"),
            ("Mode.SEALED if the socket closes.", "Mode.SEALED"),
            ("-1 if the socket closes.", "-1"),
        ]
        for clause, literal in cases:
            hit = extract_literal_answer("What does Probe.read return when the socket closes?",
                                         "read(): Returns: " + clause, "Probe")
            self.assertEqual(hit.answer, literal)
            self.assertEqual(hit.citation, clause.split(" and false otherwise", 1)[0])

    def test_abstains_from_inversion_alternatives_and_wrong_owner(self):
        cases = [
            ("What does Pipe.send return if the stream closes?", "true if the stream does not close."),
            ("What does Pipe.send return if the stream closes?", "true if the stream closes or a timeout occurs."),
            ("What does Pipe.send return if the stream closes?", "true if the stream closes; false if the stream closes."),
            ("What does Pipe.send return if the stream closes?", "true if the stream closes and false if the stream closes."),
            ("What does Socket.send return if the stream closes?", "false if the stream closes."),
            ("What does Pipe.send not return if the stream closes?", "false if the stream closes."),
        ]
        for question, clause in cases:
            self.assertIsNone(extract_literal_answer(question, "send(): Returns: " + clause, "Pipe"))

    def test_uses_exact_documented_exception_condition(self):
        row = "send(): Throws: IoException - if the socket closes. LimitException - if the queue overflows."
        hit = extract_literal_answer("Which exception does Send.send throw when the queue overflows?",
                                     row, "Send")
        self.assertEqual(hit.answer, "LimitException")
        self.assertEqual(hit.citation, "LimitException - if the queue overflows.")
        self.assertIsNone(extract_literal_answer("Which exception does Send.send throw if the socket is open?",
                                                 row, "Send"))
