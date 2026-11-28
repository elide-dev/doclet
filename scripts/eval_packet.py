"""One answer turn over ranked, independently citable documentation excerpts."""
import re


_ID = re.compile(r"\[\s*[+-]?\d+\s*\]")
_REPLY = re.compile(r"\[([1-9]\d*)\][ \t]+([^\r\n]+)")
_UNKNOWN = re.compile(r"^unknown[.!]?$", re.I)
_DECLARATION_QUESTION = re.compile(
    r"^(?:(?:On|In|For)\s+`?[\w.$]+`?\s*,\s*)?Which\s+"
    r"(?P<descriptor>(?:[\w.$`-]+\s+){0,5}?)(?:method|member|function|field|constant|predicate|property)\b"
    r"(?!['’]s\b|\s+(?:arguments?|parameters?|exceptions?|errors?|return\b|result\b|types?|"
    r"modifiers?|signatures?|values?|names?|overloads?)\b)", re.I)
_NON_DECLARATION_DESCRIPTOR = re.compile(
    r"\b(?:is|are|does|do|can|will|should|could|would|arguments?|parameters?|exceptions?|errors?|"
    r"return|result|type|value|signature)\b", re.I)
_MEMBER_ANCHOR = re.compile(r"([A-Za-z_$][\w$]*)(?:\([^()\r\n]*\))?")


def _line(candidate):
    return f"[{candidate['id']}] {candidate['excerpt']}"


def pack_candidates(candidates, max_chars=600):
    """Take ranked complete excerpts that fit, counting IDs and line separators."""
    if max_chars <= 0:
        raise ValueError("The evidence budget must be positive")
    packet = []
    seen = set()
    used = 0
    for candidate in candidates:
        excerpt = candidate["excerpt"].strip()
        key = (candidate["path"], candidate["anchor"])
        if not excerpt or key in seen:
            continue
        framed = dict(candidate, excerpt=excerpt, id=len(packet) + 1)
        cost = len(_line(framed)) + bool(packet)
        if used + cost <= max_chars:
            packet.append(framed)
            seen.add(key)
            used += cost
    return packet


def packet_prompt(question, candidates):
    """Ask for one short answer and exactly one evidence label."""
    evidence = "\n".join(map(_line, candidates))
    return ("Use one numbered excerpt. Reply on one line as [id] shortest correct answer, "
            "or unknown if no single excerpt supports an answer.\n"
            f"Question: {question}\n{evidence}")


def parse_packet_reply(text, candidates):
    """Resolve one exact citation or normalize a missing/invalid answer to unknown."""
    line = text.strip()
    match = _REPLY.fullmatch(line)
    if not match or len(_ID.findall(line)) != 1:
        return None, "unknown"
    raw_id, answer = match.groups()
    answer = answer.strip()
    if not answer or _UNKNOWN.fullmatch(answer):
        return None, "unknown"
    # Compare written IDs directly to avoid accepting altered forms (01, +1, etc.).
    matches = [candidate for candidate in candidates if str(candidate["id"]) == raw_id]
    return (matches[0], answer) if len(matches) == 1 else (None, "unknown")


def _identifier_choices(candidates):
    choices = []
    for index, candidate in enumerate(candidates, 1):
        name = _MEMBER_ANCHOR.fullmatch(candidate.get("anchor") or "")
        key = candidate.get("id", index)
        if not name or type(key) is not int or key != index:
            return []
        choices.append((key, name[1], candidate))
    return choices


def identifier_prompt(question, candidates):
    """Use a bare citation ID for a question that asks for a declaration's name."""
    choices = _identifier_choices(candidates)
    target = _DECLARATION_QUESTION.search(question.strip())
    if not choices or not target or _NON_DECLARATION_DESCRIPTOR.search(target["descriptor"]):
        return None
    evidence = "\n".join(f"[{key}] {candidate['excerpt']}" for key, _, candidate in choices)
    return ("Reply with one number identifying the declaration whose excerpt supports the answer. "
            "Reply 0 if no excerpt supports it.\n"
            f"Question: {question}\n{evidence}")


def parse_identifier_reply(text, candidates):
    """Resolve a bare ID to its source and the declaration's Java identifier."""
    raw_id = text.strip()
    for key, name, candidate in _identifier_choices(candidates):
        if raw_id == str(key):
            return candidate, name
    return None, "unknown"
