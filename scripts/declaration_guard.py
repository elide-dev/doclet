"""Guard a lexical local answer against contradictory declaration prose."""
import re

from eval_extract import terms


NEGATION = re.compile(
    r"\b(?:do|does|did|can|could|should|will|would|must|may|is|are|was|were)\s+not\b"
    r"|\b[a-z]+n['’]t\b|\b(?:never|cannot|without)\b",
    re.I,
)
CLAUSE = re.compile(r"[;!?]|\.(?=\s|$)|\b(?:but|however|whereas)\b", re.I)
FILLER = {"method", "instance", "constant", "type", "factory", "class", "one", "can",
          "do", "does", "did", "is", "are", "like", "that", "these", "those", "they"}


def _stems(text):
    # Match "separate" against "separators" or "encode" against "encoding".
    result = set()
    for word in terms(text):
        if word in FILLER or len(word) < 4:
            continue
        stem = re.sub(r"(?:ation|ing|or|ion|er|ed)$", "", word)
        result.add(stem.removesuffix("e") if len(stem) > 4 else stem)
    return result


def description_overlap(question, passage):
    """Count distinct descriptive terms shared with the question."""
    return len(_stems(question) & _stems(passage))


def contradicts_description(question, passage):
    """Abstain if a negated clause overlaps materially with a positive lookup."""
    if NEGATION.search(question):
        return False
    target = _stems(question)
    for clause in CLAUSE.split(passage):
        for marker in NEGATION.finditer(clause):
            action = clause[marker.end():].strip()
            if re.match(r"only\b", action, re.I):
                continue
            overlap = target & _stems(action)
            if len(overlap) >= 2:
                return True
    return False
