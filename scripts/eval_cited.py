"""Extract exact deprecation and default-value answers from documented declarations."""
import re

from eval_extract import Extraction, NAME


QUALIFIED = rf"{NAME}(?:[.#]{NAME})*"
REFERENCE = rf"{QUALIFIED}(?:\([^()\n]*\))?"
LITERAL = (rf'(?:null|true|false|[-+]?(?:0[xX][0-9a-fA-F]+|\d+(?:\.\d+)?)(?:[lLfFdD])?'
           rf'|"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)\'|(?:{NAME}\.)*[A-Z][A-Z0-9_]*)')
_UNSAFE = re.compile(
    r"\b(?:if|when|unless|except|otherwise|alternatively|or|either|only|provided|"
    r"versus|compare|rather|why|not)\b", re.I)
_DEPRECATED = re.compile(r"(?<![\w-])Deprecated(?:\s+since\s+[^\s]+)?[.:]")
_ANNOTATION = re.compile(r"(?<!\w)@(?:java\.lang\.)?Deprecated\b")
_FOLLOWING_FIELD = re.compile(
    r"(?=\s+(?:Returns|Throws|Parameters|Type Parameters|See Also|Overrides|Since|Default(?: Value)?):|$)")
_DEFAULT = re.compile(r"\bDefault(?: Value)?:\s*", re.I)
_PARAMETER = re.compile(rf"\b(?:parameter|argument)\s+`?(?P<name>{NAME})`?", re.I)
_REVERSE = re.compile(r"\b(?:which|what)\b\s+(?P<descriptor>[\w.$#` ]{0,90}?)"
                      r"\b(?:members?|methods?|constants?|predicates?)\b", re.I)
_REVERSE_UNSAFE = re.compile(
    r"\b(?:without|never|no|cannot|instead|against|opposite|opposes|parameters?|arguments?|"
    r"returns?|result type|default|deprecated|replacement)\b", re.I)
_STOP = set(("a an the and to of in for on when is are that which what with whether "
             "can should does do member method constant predicate named function get set has "
             "check test allow enable contain create make new true yes also this its").split())
_OPPOSITES = (("enable", "disable"), ("allow", "deny"), ("include", "exclude"), ("add", "remove"),
              ("accept", "reject"), ("start", "stop"), ("open", "close"), ("increase", "decrease"),
              ("encode", "decode"), ("serialize", "deserialize"), ("compress", "decompress"))


def _concepts(prose):
    # Split conventional API names before case-folding the same vocabulary as prose.
    words = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", prose)
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", words)
    result = set()
    for word in re.findall(r"[a-z0-9]+", words.casefold()):
        if word.endswith("ization"):
            word = word.removesuffix("ization") + "ize"
        elif word.endswith("ssion"):
            word = word.removesuffix("ion")
        elif word.endswith(("sses", "ches", "shes", "xes")):
            word = word.removesuffix("es")
        elif len(word) > 4 and word.endswith("s") and not word.endswith(("ss", "us")):
            word = word.removesuffix("s")
        if word not in _STOP and len(word) > 2:
            result.add(word)
    return result


def _opposing(left, right):
    def acts(text, verb):
        return bool(re.search(rf"\b{verb}(?:s|es|d)?\b", text, re.I))

    return any(acts(left, a) and acts(right, b) or acts(left, b) and acts(right, a)
               for a, b in _OPPOSITES)


def _identifier_answer(question, row, label, owner):
    ask = _REVERSE.search(question)
    if (not ask or _REVERSE_UNSAFE.search(question) or _UNSAFE.search(question)
            or re.search(r"\b(?:methods|members|constants|predicates)\b", ask[0], re.I)):
        return None
    # An explicit source owner may appear before the question, or in its noun phrase.
    prefix = question[:ask.start()].strip()
    prefix_owner = re.fullmatch(rf"(?:On|In|For)\s+`?({QUALIFIED})`?\s*,", prefix, re.I)
    if (prefix and not prefix_owner or re.search(
            r"\b(?:is|are|do|does|has|have|can|could|would|will)\b", ask["descriptor"], re.I)):
        return None
    named = re.findall(r"(?<!\w)(?:[a-z][\w$]*\.)*[A-Z][\w$]*(?:\.[A-Z][\w$]*)*", ask["descriptor"])
    if prefix_owner:
        named.append(prefix_owner[1])
    if any(name != owner and not name.endswith("." + owner) for name in named):
        return None
    member = label["member"]
    if re.search(rf"(?<![\w$]){re.escape(member)}(?![\w$])", question):
        return None  # The question already names it, so it is asking about the declaration.
    identifier_terms = _concepts(member)
    requested = _concepts(question) - _concepts(owner)
    if not requested & identifier_terms:
        return None
    # Inspect complete documentation sentences after the repeated declaration header.
    # Avoid matching the signature, parameter descriptions, and alternative clauses.
    body = row[label.end():]
    signature = re.match(r"\s*\([^()]*\)\s*:", body)
    if signature:
        body = body[signature.end():]
    first_field = _FOLLOWING_FIELD.search(body).start()
    body = body[:first_field].strip()
    body = re.sub(rf"^{re.escape(member)}\s*\([^()]*\)\s*:\s*", "", body)
    signature = re.match(r"^[^.!?]*?(?<![\w$])" + re.escape(member) + r"\s*\([^()]*\)\s+", body)
    if signature:
        body = body[signature.end():]
    for assertion in re.finditer(r"(?:^|(?<=[.!?])\s+)([A-Z][^.!?]*\.)", body):
        clause = assertion[1]
        if _UNSAFE.search(clause) or _REVERSE_UNSAFE.search(clause) or _opposing(question, clause):
            continue
        overlap = _concepts(clause) & requested
        if overlap & identifier_terms and overlap - identifier_terms:
            return Extraction(member, row, "described-identifier")
    return None


def _bound(question, member, owner):
    owner_pattern = rf"(?:{NAME}\.)*{re.escape(owner)}"
    return bool(re.search(
        rf"(?<![\w.$#]){owner_pattern}[.#]{re.escape(member)}(?![\w$])", question
    ))


def _unique(matches, rule):
    values = {m[0] for m in matches}
    if len(values) != 1:
        return None
    answer, citation = matches[0]
    return Extraction(answer, citation, rule)


def _replacement(row, tag):
    # The first contract after an explicit deprecation must actually name a replacement.
    # Trailing explanatory or conditional text can narrow the recommendation.
    end = _FOLLOWING_FIELD.search(row, tag.end()).start()
    detail = row[tag.end():end].strip()
    if _UNSAFE.search(detail):
        return None
    pattern = re.compile(
        rf"^(?:Use|Replaced by|Prefer)\s+`?(?P<answer>{REFERENCE})`?(?:\s+instead)?(?:\.(?=\s|$)|$)",
        re.I,
    )
    match = pattern.match(detail)
    if match and not re.search(r"\b(?:use|prefer|replaced by)\b", detail[match.end():], re.I):
        start = tag.end() + len(row[tag.end():end]) - len(row[tag.end():end].lstrip())
        return Extraction(match["answer"], row[tag.start():start + match.end()], "deprecation-replacement")
    return None


def _defaults(row, question):
    parameter = _PARAMETER.search(question)
    matches = []
    if parameter:
        name = parameter["name"]
        param_field = re.compile(rf"\bParameters?:\s+{re.escape(name)}\s+-\s+")
        defaults = re.compile(rf"^(?:Defaults? to|The default (?:value )?is)\s+`?(?P<value>{LITERAL})`?\.?$")
        for field in param_field.finditer(row):
            end = _FOLLOWING_FIELD.search(row, field.end()).start()
            # Require exactly a simple parameter fact, without a hidden exception.
            match = defaults.fullmatch(row[field.end():end].strip())
            if match is None:
                return None
            matches.append((match["value"], row[field.start():end].strip()))
    elif not re.search(r"\b(parameters?|arguments?)\b", question, re.I):
        for field in _DEFAULT.finditer(row):
            end = _FOLLOWING_FIELD.search(row, field.end()).start()
            match = re.fullmatch(rf"`?(?P<value>{LITERAL})`?\.?", row[field.end():end].strip())
            if match is None:
                return None
            matches.append((match["value"], row[field.start():end].strip()))
    return _unique(matches, "documented-default")


def extract_cited_answer(question, excerpt, owner):
    """Require a qualified member and one unambiguous cited declaration fact."""
    row = excerpt.strip()
    label = re.match(rf"(?:- )?(?P<member>{NAME})(?=[\s:(])", row)
    if not label or "\n" in row or "…" in row or "..." in row or _UNSAFE.search(question):
        return None
    if not _bound(question, label["member"], owner):
        return _identifier_answer(question, row, label, owner)
    if re.search(r"\b(default|defaults)\b", question, re.I):
        return _defaults(row, question)
    tags = list(_DEPRECATED.finditer(row))
    if re.search(r"\b(replace\w*|alternative|instead)\b", question, re.I):
        return _replacement(row, tags[0]) if len(tags) == 1 else None
    member_ref = rf"(?:{NAME}\.)*{re.escape(owner)}[.#]{re.escape(label['member'])}(?:\([^()]*\))?"
    if re.fullmatch(rf"Is\s+`?{member_ref}`?\s+(?:marked\s+)?deprecated\?", question.strip(), re.I):
        tag = tags[0] if len(tags) == 1 else _ANNOTATION.search(row)
        if tag:
            return Extraction("yes", tag[0], "declared-deprecated")
    return None
