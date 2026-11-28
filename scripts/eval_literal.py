"""Read explicit literal results from complete documentation clauses."""
import re

from eval_extract import Extraction, NAME, _TAG, _scoped_member


_LITERAL = rf"(?:null|true|false|[-+]?\d+(?:\.\d+)?|(?:{NAME}\.)*[A-Z][A-Z0-9_]*)"
_CONDITION = re.compile(r"\s+(if|when|in case)\s+(.+)$", re.I)
_COMPARISON = re.compile(r"\b(?:and|or|unless|except|otherwise|either|only)\b", re.I)
_WORDS = re.compile(r"[\w]+|[<>=!]+")


def _condition(value):
    clean = value.casefold().strip().strip(".?!")
    # Common equivalent Java documentation phrases for absent entries.
    absence = re.fullmatch(r"(?:there (?:is|are) )?no such (.+?)(?: exists?)?", clean)
    if not absence:
        absence = re.fullmatch(r"(.+?) (?:does|do) not exist", clean)
    if absence:
        clean = "absent " + absence[1]
    words = tuple(w for w in _WORDS.findall(clean) if w not in {"a", "an", "the", "is", "are"})
    return words if words and not _COMPARISON.search(clean) else ()


def _clauses(body):
    # A period inside an identifier or decimal is not a clause delimiter.
    separators = (rf"(?<=[.;])\s+|\s+(?:and|or)\s+(?=(?:returns?\s+)?{_LITERAL}"
                  r"\s+(?:otherwise|if|when)\b)")
    return (clause.strip() for clause in re.split(separators, body) if clause.strip())


def _fields(row, field):
    tags = list(_TAG.finditer(row))
    for i, tag in enumerate(tags):
        if tag[1] == field:
            end = tags[i + 1].start() if i + 1 < len(tags) else len(row)
            yield row[tag.end():end].strip()


def extract_literal_answer(question, excerpt, page):
    """Return a cited literal when a scoped question matches a stated condition."""
    row = excerpt.strip()
    if not row or "\n" in row or "…" in row:
        return None
    label = re.match(rf"(?:- )?(?P<name>{NAME})(?=[\s:(])", row)
    qualifier = _CONDITION.search(question.rstrip().removesuffix("?"))
    if not label or not qualifier or not _scoped_member(question, label["name"], page):
        return None
    stem = question[:qualifier.start()]
    condition = _condition(qualifier[2])
    if not condition or not re.match(r"^(?:what|which)\b", stem, re.I):
        return None
    if re.search(r"\b(?:not|never|why|compare|instead|rather)\b", stem, re.I):
        return None
    if re.search(r"\breturns?\b", stem, re.I):
        matches = _return_matches(row, condition)
    elif re.search(r"\b(?:exception|error)\b", stem, re.I) and re.search(r"\bthrows?\b", stem, re.I):
        matches = _throws_matches(row, condition)
    else:
        return None
    answers = {answer for answer, _ in matches}
    if len(answers) != 1:
        return None
    answer, citation = matches[0]
    return Extraction(answer, citation, "conditional-literal")


def _return_matches(row, condition):
    parsed = []
    pattern = re.compile(
        rf"(?:(?i:returns?)\s+)?(?P<value>{_LITERAL})\s+(?i:if|when)\s+(?P<condition>.+)$")
    for body in _fields(row, "Returns"):
        for clause in _clauses(body):
            match = pattern.match(clause)
            if match and _condition(match["condition"].rstrip(".;")) == condition:
                parsed.append((match["value"], clause))
    return parsed


def _throws_matches(row, condition):
    parsed = []
    pattern = re.compile(rf"(?<!\S)((?:{NAME}\.)*[A-Z][\w$]*)\s+-\s+(?:if|when)\s+")
    for body in _fields(row, "Throws"):
        entries = list(pattern.finditer(body))
        if not entries or entries[0].start() != 0:
            continue
        for i, entry in enumerate(entries):
            end = entries[i + 1].start() if i + 1 < len(entries) else len(body)
            clause = body[entry.start():end].strip()
            if _condition(body[entry.end():end].rstrip(".; ")) == condition:
                parsed.append((entry[1], clause))
    return parsed
