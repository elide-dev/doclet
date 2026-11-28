"""Budget complete documentation fields for the same local-first reader."""
from html import unescape
import re

from eval_discovery import Member, member_passage
from eval_extract import extract_contract_answer, terms
from eval_literal import extract_literal_answer
from eval_rank import rank_contract


# These headings occur in normalized declaration contracts and JDK notes.
FIELD = re.compile(r"(?<!\S)(Returns|Throws|Parameters|Type Parameters|Since|See Also|"
                   r"Overrides|Specified by|Deprecated|API Note|Implementation Requirements|"
                   r"Implementation Note):\s*")
SENTENCE = re.compile(r"(?<=[.!?])\s+(?=[A-Z`])")
MARKDOWN_LINK = re.compile(r"\[([^]\n]+)\]\([^\n)]*\)")
CALLABLE = re.compile(r"\((.*)\)$")
FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$", re.M)


def markdown_members(body):
    """Split Markdown at declaration headings outside of fenced examples."""
    fenced = []
    fence = None
    for candidate in FENCE.finditer(body):
        marker = candidate[1]
        if fence is None:
            fence = candidate
        elif (marker[0] == fence[1][0] and len(marker) >= len(fence[1])
              and not candidate[2].strip()):
            fenced.append((fence.start(), candidate.end()))
            fence = None
    if fence is not None:
        fenced.append((fence.start(), len(body)))
    hidden = iter(fenced)
    region = next(hidden, None)
    heading_positions = []
    for heading in re.finditer(r"^(#{1,3}) ([^\n]+)\n", body, re.M):
        while region and heading.start() >= region[1]:
            region = next(hidden, None)
        if not region or heading.start() < region[0]:
            heading_positions.append(heading)
    for i, heading in enumerate(heading_positions):
        if len(heading[1]) != 3:
            continue
        end = heading_positions[i + 1].start() if i + 1 < len(heading_positions) else len(body)
        block = body[heading.end():end]
        tag = re.match(r'<a id="([^"\n]+)"></a>\n', block)
        yield heading[2], unescape(tag[1]) if tag else None, block[tag.end():] if tag else block


def markdown_detail(body, member, card):
    """Use the exact signature when display headings collapse overloads."""
    if not member.anchor:
        return member_passage(body, "markdown", member)
    signature = " ".join(card["signature"].split())
    sections = list(markdown_members(body))
    anchored = [(anchor, section) for _, anchor, section in sections if anchor]
    if anchored:
        matches = [section for anchor, section in anchored if anchor == member.anchor]
    else:
        matches = _legacy_sections(sections, member, signature)
    if len(matches) != 1:
        return ""
    return markdown_member_passage(member.label, matches[0])


def markdown_member_passage(label, body):
    block = re.sub(r"^`{3,}[^\n]*\n.*?\n`{3,}\s*$", "", body, flags=re.M | re.S)
    block = MARKDOWN_LINK.sub(r"\1", block).replace("`", "")
    block = re.sub(r"^\*\*[^*\n]*\bReturns\*\* [^:\n]*:\s*", "Returns: ", block, flags=re.M)
    block = re.sub(r"^\*\*([^*\n]+)\*\*\s*$", r"\1:", block, flags=re.M)
    return label + ": " + " ".join(block.split())


def _legacy_sections(sections, member, signature):
    matches = []
    for _, _, section in sections:
        code = re.search(r"^`{3,}[^\n]*\n(.*?)\n`{3,}\s*$", section, re.M | re.S)
        if code and " ".join(code[1].split()).endswith(signature):
            matches.append(section)
    if len(matches) != 1:
        # The display title may be enough when it maps to one section on the page.
        expected = CALLABLE.search(member.label)
        args = tuple(_simple_name(part) for part in expected[1].split(",")) if expected else None
        matches = []
        for title, _, section in sections:
            title = title.replace("\\", "")
            actual = CALLABLE.search(title)
            display_args = tuple(_simple_name(part) for part in actual[1].split(",")) if actual else None
            if title.partition("(")[0] == member.label.partition("(")[0] and args == display_args:
                matches.append(section)
    return matches


def _simple_name(type_name):
    return type_name.strip().rsplit(".", 1)[-1]


def _fields(passage):
    tags = list(FIELD.finditer(passage))
    head = passage[:tags[0].start()].strip() if tags else passage
    facts = []
    for i, tag in enumerate(tags):
        end = tags[i + 1].start() if i + 1 < len(tags) else len(passage)
        facts.append((tag[1], passage[tag.start():end].strip()))
    return head, facts


def _preferences(question):
    q = question.casefold()
    if re.search(r"\b(throw\w*|exception|fail\w*|reject\w*)\b", q):
        return ("Throws",)
    if re.search(r"\b(return\w*|result|output)\b", q):
        return ("Returns",)
    return ()


def select_evidence(question, member, passage, chars):
    """Choose complete facts, never a truncated return or exception contract."""
    passage = " ".join(passage.split())
    head, facts = _fields(passage)
    sentences = [s for s in SENTENCE.split(head) if s]
    groups = {}
    for label, fact in facts:
        groups.setdefault(label, []).append(fact)
    choices = dict(enumerate([*sentences, *(" ".join(g) for g in groups.values())]))
    title = member.label + ":"
    left = chars - len(title)
    selected = []
    preferred = set(_preferences(question))
    order = rank_contract(question, choices) if choices else []
    order.sort(key=lambda key: FIELD.match(choices[key])[1] not in preferred
               if FIELD.match(choices[key]) else True)
    for key in order:
        text = choices[key]
        if len(text) + 1 <= left:
            selected.append(key)
            left -= len(text) + 1
    return " ".join([title, *(choices[key] for key in sorted(selected))]) if left >= 0 else ""


def needs_detail(question, member, excerpt):
    """Use the compact card when it carries a relevant field or local answer."""
    if not member.anchor:
        return True
    if extract_adaptive_answer(question, excerpt, member.owner):
        return False
    head, fields = _fields(excerpt)
    preferred = _preferences(question)
    query = set(terms(question)) - set(terms(member.owner + " " + member.label))
    present = set(terms(head))
    if preferred:
        matching = [fact for tag, fact in fields if tag in preferred]
        if not matching:
            return True
        present.update(terms(" ".join(matching)))
    return not query or len(query & present) < min(2, len(query))


def extract_adaptive_answer(question, excerpt, owner):
    return (extract_literal_answer(question, excerpt, owner)
            or extract_contract_answer(question, excerpt, owner))
