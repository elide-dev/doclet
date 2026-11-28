"""Conservative single-member lookup from a retrieved contract, without model or fixture access."""
from dataclasses import dataclass
from difflib import SequenceMatcher
import re


@dataclass(frozen=True)
class Extraction:
    answer: str
    citation: str
    rule: str


NAME = r"[A-Za-z_$][\w$]*"
# Keep polarity, quantifiers and 'without': they distinguish otherwise similar contracts.
STOP = set("a an as at by for from in into is it of on or that the this to what which with".split())


def terms(text):
    """Normalize capitalization and regular plurals for phrase matching."""
    words = re.findall(r"[a-z0-9]+", text.lower())
    return [w.removesuffix("s") if len(w) > 3 and not w.endswith("ss") else w
            for w in words if w not in STOP]


def _answer(value, citation, rule):
    if len(value) > 200 or value.endswith(("…", "...")):
        return None
    return Extraction(value, citation, rule)


def extract_answer(question, excerpt, page):
    """Return a verbatim answer with source span, or abstain on unsupported lookup shapes.

    Both Javadoc member-detail text and Markdown contract bullets expose the member
    name as the first token. Use that common representation for the lookup rules.
    """
    row = excerpt.strip()
    if not row or "\n" in row:
        return None
    label = re.match(rf"(?:- )?(?P<name>{NAME})(?=[\s:(])", row)
    if not label:
        return None
    member = label["name"]
    head = row[label.end():]
    # A qualified member in the question binds both the owner and the member.
    references = re.findall(rf"\b((?:[A-Z][\w$]*\.)*[A-Z][\w$]*)\.([a-z_$][\w$]*)\b", question)
    if any((owner != page and not owner.endswith("." + page)) or name != member for owner, name in references):
        return None
    named = bool(references or re.search(rf"(?<!\w){re.escape(member)}(?:\s*\(|\b)", question))
    lower = question.lower()
    # A new condition changes the question. Require its material words in the
    # contract, so "if the disk is full" cannot answer "if the network is down".
    qualifier = re.search(r"\s+(?:if|when|unless)\s+(.+?)\??$", lower)
    if qualifier:
        condition = set(terms(qualifier[1]))
        if condition.difference(terms(row)):
            return None
    if re.search(r"\b(why|compare|versus|instead of|except for)\b", lower):
        return None
    if re.search(r"\b(replace\w*|alternative)\b", lower) and "deprecat" in lower:
        if not re.search(rf"(?<!\w){re.escape(member)}(?!\w)", question) or "deprecated" not in head.lower():
            return None
        suggestions = list(re.finditer(rf"\bUse\s+({NAME}(?:[.#]{NAME})*\([^()]*\))", head, re.I))
        if len(suggestions) == 1:
            return _answer(suggestions[0][1], suggestions[0][0], "deprecation-replacement")
        return None
    throws = list(re.finditer(rf"\bThrows\s*:?\s+({NAME}(?:\.{NAME})*)\s+(?:-\s*)?([^.;]+?)(?=\s+Throws\b|[.;]|$)", head))
    # Javadoc flattens multiple typed exceptions after a single "Throws:" heading.
    if "Throws:" in head and head.split("Throws:", 1)[1].count(" - ") > 1:
        return None
    if lower.startswith("when ") and named and len(throws) == 1:
        condition = re.match(r"(?:if|when|unless)\s+.+", throws[0][2], re.I)
        if condition and re.search(r"\b(throw\w*|reject\w*|fail\w*)\b", lower):
            return _answer(condition[0], throws[0][0], "throws-condition")
    if (lower.startswith(("what ", "which ")) and re.search(r"\b(exception|error)\b", lower) and re.search(r"\bthrow\w*\b", lower)
            and len(throws) == 1 and (references or page in question)):
        return _answer(throws[0][1], throws[0][0], "throws-type")

    if named and re.search(r"\bwhat\b", lower):
        # A unique explicitly documented return mentioning the requested quantity.
        subject = lower.split(" does ", 1)[0]
        if re.search(r"\breturn\w*\b", lower) or " does " in lower:
            tagged = list(re.finditer(r"\bReturns:?\s+([^.;]+?)(?=\s+Throws[: ]|[.;]|$)", head))
            requested = set(terms(subject))
            # An explicit structured field outranks prose beginning with "Returns".
            explicit = [m for m in tagged if m[0].startswith("Returns:")]
            tagged = explicit or tagged
            if re.search(r"\breturn(?:s)?\??$", lower):
                candidates = tagged
            else:
                candidates = [m for m in tagged if requested.intersection(terms(m[1]))]
            if len(candidates) == 1:
                return _answer(candidates[0][1], candidates[0][0], "return-description")

    # Reverse lookup: identify a declaration by matching a distinctive documented
    # phrase. Only answer identifier questions, never how/when/value questions.
    if not lower.startswith(("which ", "what ")) or re.search(r"\b(does|is returned|return type)\b", lower):
        return None
    asks_member = (lower.startswith("which ") and member.isupper() or
                   re.search(r"\b(name|method|constant|field|property|component|function)\b", lower))
    if not asks_member:
        return None
    query_terms = terms(question)
    for sentence in re.split(r"[.!?;]", head):
        doc_terms = terms(sentence)
        match = SequenceMatcher(None, query_terms, doc_terms, autojunk=False).find_longest_match()
        if match.size < 2 or len(set(query_terms[match.a:match.a + match.size])) < 2:
            continue
        # An unmentioned negation can reverse a positive match.
        if any((word in query_terms) != (word in doc_terms) for word in ("not", "never", "without")):
            continue
        if match.size >= 3 or (match.size == 2 and match.size >= len(query_terms) / 3):
            return _answer(member, row, "member-description")
    return None
