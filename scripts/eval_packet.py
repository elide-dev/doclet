"""One answer turn over ranked, independently citable documentation excerpts."""
import re


_ID = re.compile(r"\[\s*[+-]?\d+\s*\]")
_REPLY = re.compile(r"\[([1-9]\d*)\][ \t]+([^\r\n]+)")
_UNKNOWN = re.compile(r"^unknown[.!]?$", re.I)


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
