from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

from declaration_guard import contradicts_description
from eval_discovery import Member
from eval_evidence import markdown_member_passage, select_evidence


class DescriptionGuardTest(unittest.TestCase):
    def test_negation_in_adjacent_sentence_reverses_description(self):
        question = "Which setting treats spaces like delimiters that separate repeated values?"
        prose = ("Treat spaces as whitespace delimiters. "
                 "They do not act as separators for repeated values.")
        self.assertTrue(contradicts_description(question, prose))

    def test_noncontradictory_negation_does_not_block_answer(self):
        question = "Which setting treats spaces like delimiters that separate repeated values?"
        prose = ("This setting is never deprecated. "
                 "It doesn't alter tabs, but spaces act as separators for repeated values.")
        self.assertFalse(contradicts_description(question, prose))

    def test_not_only_is_not_a_reversal(self):
        question = "Which method sorts repeated values?"
        prose = "It does not only sort repeated values. It can merge them too."
        self.assertFalse(contradicts_description(question, prose))

    def test_since_line_does_not_hide_prose_from_budget(self):
        prose = ("Since 2.1.0.\n\n"
                 "A long usage example would distract from the actual purpose. "
                 "A short example can be useful when demonstrating the output. "
                 "Use the fallback if the header is absent.\n\n"
                 "**Parameters**\n\n"
                 "- flag: controls the setting\n")
        member = Member("pkg/Example", "open(boolean)", "open(boolean)", "")
        passage = markdown_member_passage(member.label, prose, normalize_contracts=True)
        excerpt = select_evidence("When does open use the fallback if the header is absent?",
                                  member, passage.removeprefix(member.label + ": "), 80)
        self.assertIn("Use the fallback if the header is absent.", excerpt)
        self.assertTrue(passage.endswith("Since: 2.1.0"))

    def test_conditional_group_beats_incidental_repetition(self):
        passage = ("The marker may appear in a marker log or in marker diagnostics. "
                   "A marker with an explicit override is preserved. "
                   "Otherwise, the marker is translated if the target is enabled. "
                   "Some marker diagnostics include the translation target.")
        member = Member("pkg/Example", "translate()", "translate()", "")
        question = "When does translate change the marker instead of preserving it?"
        excerpt = select_evidence(question, member, passage, 127, linked=True)
        self.assertIn("A marker with an explicit override is preserved. Otherwise,", excerpt)
        self.assertIn("if the target is enabled.", excerpt)
        self.assertLessEqual(len(excerpt), 127)

    def test_small_budget_never_splits_dependent_group(self):
        passage = ("An enabled override preserves the value. "
                   "In that case, the caller must clear the override to discard it. "
                   "Other settings apply independently.")
        member = Member("pkg/Example", "clear()", "clear()", "")
        excerpt = select_evidence("When is a caller allowed to discard the value?",
                                  member, passage, 66, linked=True)
        self.assertNotIn("In that case", excerpt)
        self.assertNotIn("preserves the value", excerpt)
        self.assertLessEqual(len(excerpt), 66)


if __name__ == "__main__":
    unittest.main()
