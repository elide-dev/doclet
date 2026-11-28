"""Read explicit Java return-type constraints for declaration retrieval."""
import re


_PRIMITIVE = r"boolean|byte|char|double|float|int|long|short|void"
_REFERENCE = r"(?:[\w$]+\.)*[A-Z][\w$]*"
_TYPE = rf"(?:\b(?:{_PRIMITIVE})\b|{_REFERENCE})(?:\s*\[\s*\])*"
_INTENT = re.compile(r"\b(?:which|what)\b[^?!]{0,100}\b(?:method|function|member|operation)\b", re.I)
_OUTPUT = re.compile(r"\b(?:return type (?:is|of)|returns?|returning)\s+", re.I)
_TYPE_PHRASE = re.compile(
    rf"^(?:(?:a|an|the)\s+)?(?:Java\s+)?`?(?P<type>{_TYPE})`?(?![\w$])")
_ARRAY_PHRASE = re.compile(r"^(?:an?\s+)?(?P<type>byte|char|short|int|long|float|double|boolean)\s+array\b")
_ALIAS = re.compile(r"^(?:raw\s+)?(?P<type>bytes|chars)\b")
_CONTRAST = re.compile(r"\b(?:rather than|instead of)\b", re.I)
_QUALIFIER = re.compile(r"[,;?!]|\s+\b(?:without|with|for|from|of|after|before|when|if|that|which|"
                        r"using|or|and|versus|vs)\b", re.I)
_ALTERNATIVE = re.compile(r"\s*(?:[</]|,?\s*(?:or|and|versus|vs)\b)", re.I)
_NEGATED_TYPE = re.compile(r"\b(?:not|never|no)\b|n['’]t\b", re.I)
_NATURAL_TYPES = {"string": "String", "integer": "int", "boolean": "boolean", "bytes": "byte[]",
                  "chars": "char[]", "character": "char"}
_COMMON_REFERENCES = set("String CharSequence Integer Boolean Byte Short Long Float Double Character "
                         "Object Class List Map Set Iterable Iterator Optional".split())


def _type_name(value):
    value = re.sub(r"\s+", "", value)
    return value.replace("$", ".")


def _requested_head(phrase):
    """Find the type at the end of a bounded output noun phrase."""
    if item := _ARRAY_PHRASE.match(phrase):
        if not _ALTERNATIVE.match(phrase[item.end():]):
            return item["type"] + "[]"
    boundary = _QUALIFIER.search(phrase)
    head = phrase[:boundary.start()] if boundary else phrase
    rest = phrase[len(head):]
    if _ALTERNATIVE.match(rest) or _NEGATED_TYPE.search(head):
        return None
    head = head.strip().rstrip(",").strip()
    if item := _ARRAY_PHRASE.fullmatch(head):
        return item["type"] + "[]"
    if item := _ALIAS.fullmatch(head):
        return item["type"].removesuffix("s") + "[]"
    if item := _TYPE_PHRASE.fullmatch(head):
        return _type_name(item["type"])
    # Adjectives may contain uppercase acronyms or hyphenated encoding names.
    # Only a known natural type at the head of that phrase can drive filtering.
    words = head.split()
    if 1 <= len(words) <= 8 and all(re.fullmatch(r"[\w-]+", word) for word in words[:-1]):
        written = words[-1]
        word = written.strip("`")
        if word in _COMMON_REFERENCES or (
                (word != written or "." in word) and re.fullmatch(_TYPE, word)):
            return word
        return _NATURAL_TYPES.get(word)
    return None


def requested_return_type(question):
    """Recognize a declaration question with one explicit Java output type."""
    intent = _INTENT.search(question)
    if not intent:
        return None
    outputs = _CONTRAST.split(question[intent.end():], maxsplit=1)[0].rstrip(", ")
    markers = list(_OUTPUT.finditer(outputs))
    if markers and _NEGATED_TYPE.search(outputs[:markers[0].start()]):
        return None
    types = [_requested_head(outputs[marker.end():]) for marker in markers]
    if None in types:
        return None
    distinct = set(types)
    return next(iter(distinct)) if len(distinct) == 1 else None


def return_type_matches(requested, actual):
    """Compare array dimensions, preserving primitive and wrapper distinctions."""
    type_name = _type_name(actual)
    # A bare Java type asks for the declaration's outer type, regardless of
    # generic arguments. Explicit generic constraints are left to the reader.
    while re.search(r"<[^<>]*>", type_name):
        type_name = re.sub(r"<[^<>]*>", "", type_name)
    if not type_name:
        return False
    return type_name == requested or "." not in requested and type_name.endswith("." + requested)


def prefer_return_type(question, members):
    """Retain compatible declarations. Unknown types remain a fallback."""
    requested = requested_return_type(question)
    if requested is None:
        return members
    known = [member for member in members if return_type_matches(requested, member.return_type)]
    return known or [member for member in members if not member.return_type]
