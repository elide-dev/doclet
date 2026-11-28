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


# Version 2 consumes complete tagged fields. Sentence boundaries and semicolons
# occur inside contracts (e.g., alternative return values), so only a new field
# heading terminates the previous one.
_TAG = re.compile(
    r"(?<!\S)(Returns|Throws|Parameters|Type Parameters|Since|See Also|Overrides|"
    r"Specified by|Deprecated|API Note|Implementation Requirements|Implementation Note):\s*"
)


def _scoped_member(question, member, page):
    """Check exact owner/member references; a bare member must match a whole word."""
    refs = re.findall(rf"\b((?:{NAME}\.)*[A-Z][\w$]*)\.([a-z_$][\w$]*)\b", question)
    if refs:
        return all((owner == page or owner.endswith("." + page)) and name == member
                   for owner, name in refs)
    return bool(re.search(rf"(?<![\w.]){re.escape(member)}(?!\w)", question))


def _condition_words(text):
    # Keep order, polarity and numbers. Ignore only common articles, conditional
    # conjunctions, and copulas; "min is above max" is distinct from its reverse.
    text = re.sub(r"^(?:if|when)\s+", "", text.lower().strip())
    return [word for word in re.findall(r"[\w]+|[<>=!]+", text) if word not in ("a", "an", "the", "is", "are")]


def extract_contract_answer(question, excerpt, page):
    """Read a complete single-member excerpt. Callers must abstain on clipped input.

    Verbatim return values may include several clauses. Exception queries accept a
    unique matching condition. Other lookup shapes use v1's identifier rules.
    """
    row = excerpt.strip()
    if not row or "\n" in row or "…" in row or "..." in row:
        return None
    label = re.match(rf"(?:- )?(?P<name>{NAME})(?=[\s:(])", row)
    if not label:
        return None
    member = label["name"]
    q = question.rstrip().rstrip("?")
    qualifier = re.search(r"\s+(?:when|if|unless)\s+(.+)$", q, re.I)
    stem = q[:qualifier.start()] if qualifier else q
    is_return = bool(re.search(r"^what\b.*\breturn(?:s)?\s*$", stem, re.I))
    is_exception = bool(re.search(r"^(?:what|which)\b.*\b(?:exception|error)\b", stem, re.I)
                        and re.search(r"\bthrow(?:s|n)?\b", stem, re.I))
    legacy = None
    if not is_return and not is_exception:
        legacy = extract_answer(question, row, page)
        if not legacy or legacy.rule in ("member-description", "deprecation-replacement"):
            return legacy
        if legacy.rule == "return-description":
            # v1 looks for a shared noun. Require the rest of the requested
            # quantity too (e.g., disk capacity and queue capacity differ).
            subject = q.lower().partition(" does ")[0]
            if qualifier or set(terms(subject)).difference(terms(row)):
                return None
            is_return = True
        elif legacy.rule != "throws-condition":
            return None

    if not _scoped_member(stem, member, page):
        return None
    if re.search(r"\b(why|compare|versus|rather than|instead of)\b", q, re.I):
        return None
    headings = list(_TAG.finditer(row))
    wanted = "Returns" if is_return else "Throws"
    fields = []
    for i, heading in enumerate(headings):
        if heading[1] == wanted:
            end = headings[i + 1].start() if i + 1 < len(headings) else len(row)
            fields.append((row[heading.end():end].strip(), row[heading.start():end].rstrip()))
    if is_return:
        if len(fields) != 1 or not fields[0][0]:
            return None
        value, citation = fields[0]
        if qualifier:
            # Multiple alternatives may document different results. The entire
            # contract is returned only for an unconditional return query.
            return None
        if legacy and legacy.answer != value.removesuffix("."):
            return None
        return _answer(value, citation, "contract-return")

    candidates = []
    for value, citation in fields:
        # Standard Javadoc flattens several dt/dd pairs under one Throws heading.
        entries = list(re.finditer(rf"(?<!\S)((?:{NAME}\.)*[A-Z][\w$]*)\s+-\s+", value))
        if not entries or entries[0].start() != 0:
            return None
        for i, entry in enumerate(entries):
            end = entries[i + 1].start() if i + 1 < len(entries) else len(value)
            detail = value[entry.end():end].strip()
            if not detail:
                return None
            if qualifier:
                if qualifier[0].lstrip().lower().startswith("unless ") != detail.lower().startswith("unless "):
                    continue
                if _condition_words(qualifier[0]) != _condition_words(detail):
                    continue
            if legacy and legacy.answer != detail.removesuffix("."):
                return None
            answer = detail if legacy else entry[1]
            candidates.append((answer, value[entry.start():end].rstrip()))
    if len(candidates) == 1:
        answer, citation = candidates[0]
        return _answer(answer, citation, "contract-condition" if legacy else "contract-throws")
    return None
