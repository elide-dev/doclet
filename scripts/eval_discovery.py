"""Look up both arms through their published alphabetical declaration indexes."""
from collections import defaultdict
from dataclasses import dataclass, replace
from html import unescape
from html.parser import HTMLParser
from pathlib import PurePosixPath
import re
from urllib.parse import unquote, urlsplit
from xml.etree.ElementTree import Element, SubElement


@dataclass(frozen=True)
class Member:
    path: str
    anchor: str
    label: str
    summary: str
    search_text: str = ""
    return_type: str = ""

    @property
    def owner(self):
        return PurePosixPath(self.path).name


class Tree(HTMLParser):
    """Small tolerant tree for the JDK doclet's static HTML."""
    void = set("area base br col embed hr img input link meta param source track wbr".split())

    def __init__(self, text):
        super().__init__()
        self.root = Element("root")
        self.stack = [self.root]
        self.feed(text)
        self.close()

    def handle_starttag(self, tag, attrs):
        node = SubElement(self.stack[-1], tag, dict(attrs))
        if tag not in self.void:
            self.stack.append(node)

    def handle_endtag(self, tag):
        if tag in self.void:
            return
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                return

    def handle_data(self, data):
        node = self.stack[-1]
        if len(node):
            node[-1].tail = (node[-1].tail or "") + data
        else:
            node.text = (node.text or "") + data


def text(node):
    return " ".join("".join(node.itertext()).split()) if node is not None else ""


def _member(href, label, summary, ext):
    url = urlsplit(href)
    path = PurePosixPath(unquote(url.path))
    if url.scheme or url.netloc or path.is_absolute() or ".." in path.parts or path.suffix != ext:
        return None
    return Member(str(path.with_suffix("")), unquote(url.fragment), label, summary)


def _plain_markdown(s):
    return re.sub(r"\\([\\`*_[\]<>|])", r"\1",
                  re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)).replace("`", "")


def glance_rows(source, path):
    """Pair each published summary row's declaration with the text used for local ranking."""
    section = re.search(r"^## API at a glance\n\n((?:- [^\n]+(?:\n|$))+)", source, re.M)
    if section:
        for line in section[1].splitlines():
            row = line[2:]
            linked = re.fullmatch(r"\[(.+?)\]\(#(\S+?)\)(: .+|:)?", row)
            if linked:
                label = _plain_markdown(linked[1])
                anchor = unquote(linked[2])
                row = label + (linked[3] or "")
            else:
                label = _plain_markdown(row.partition(": ")[0])
                anchor = ""
            yield Member(path, anchor, label, row.partition(": ")[2]), row


def load_members(site_root, fmt):
    """Read member names, descriptions and location from the format's own index."""
    members = []
    if fmt == "html":
        tree = Tree((site_root / "index-all.html").read_text())
        for dl in tree.root.iter("dl"):
            if "index" not in dl.get("class", "").split():
                continue
            for term, definition in zip(dl, list(dl)[1:]):
                if term.tag != "dt" or definition.tag != "dd":
                    continue
                link = term.find("a")
                if link is None or not {"member-name-link", "type-name-link"}.intersection(link.get("class", "").split()):
                    continue
                members.append(_member(link.get("href", ""), text(link), text(definition), ".html"))
    elif fmt == "markdown":
        index = (site_root / "index-all.md").read_text()
        for match in re.finditer(r"^- \[\x60([^\x60]+)\x60\]\(([^)]+)\), ([^\n]+)", index, re.M):
            label, href, detail = match.groups()
            members.append(_member(href, label, _plain_markdown(detail.partition(": ")[2]), ".md"))
    else:
        raise ValueError(f"Unknown member index format {fmt}")
    result = [m for m in members if m is not None]
    if not result:
        raise ValueError(f"No type or member declarations in {site_root} ({fmt})")
    return result


def choose_member(question, members, rank):
    """Prefer explicit owner/member references, then rank the remaining candidates."""
    candidates = _scope(question, members)
    return _rank(question, candidates, rank, detailed=False)


def _scope(question, members):
    owners = {m.owner for m in members}
    named = [owner for owner in owners
             if re.search(r"(?<![\w$])" + re.escape(owner) + r"(?![\w$])", question)]
    # Match the most specific nested name in Planner.Options.Detail, retaining other owners.
    named = [owner for owner in named if not any(other.startswith(owner + ".") for other in named)]
    candidates = [m for m in members if m.owner in named] if named else members
    qualified = [m for m in candidates if m.anchor and re.search(
        r"(?<![\w$])" + re.escape(m.owner + "." + m.label.split("(", 1)[0]) + r"(?![\w$])", question)]
    return qualified or candidates


def _rank(question, candidates, rank, detailed):
    # Use the full path and JVM signature for ties between identically named overloads.
    entries = {m.path + "#" + m.anchor: m for m in candidates}
    descriptions = {key: f"{m.owner} {m.label} {m.summary}" for key, m in entries.items()}
    if detailed:
        descriptions = {key: desc + " " + entries[key].search_text for key, desc in descriptions.items()}
    return entries[rank(question, descriptions)[0]]


_IDENTIFIER = r"[A-Za-z_$][\w$]*"
_CALL = re.compile(
    rf"(?<![\w$])(?:(?P<owner>{_IDENTIFIER}(?:\.{_IDENTIFIER})*)[.#])?"
    rf"(?P<name>{_IDENTIFIER})\s*\((?P<args>[^()]*)\)")


def _parameter_types(parameters):
    # JDK anchors use erasure; display names and queries can retain nested generics.
    while re.search(r"<[^<>]*>", parameters):
        parameters = re.sub(r"<[^<>]*>", "", parameters)
    if not parameters.strip():
        return ()
    types = []
    for parameter in parameters.split(","):
        normalized = re.sub(r"\s+", "", parameter).replace("...", "[]").replace("$", ".")
        if not re.fullmatch(r"[\w.]+(?:\[\])*", normalized):
            return None
        types.append(normalized)
    return tuple(types)


def _overloads(question, members):
    """Resolve an explicitly written signature before lexical ranking."""
    calls = list(_CALL.finditer(question))
    if not calls:
        return []
    # A parameter name can also be the name of a documented type. It is not the owner.
    outside = _CALL.sub(lambda m: m[0][:m[0].index("(")], question)
    scoped = _scope(outside, members)
    matches = []
    for call in calls:
        wanted = _parameter_types(call["args"])
        if wanted is None:
            continue
        for m in scoped:
            if m.label.partition("(")[0] != call["name"] or "(" not in m.anchor:
                continue
            if call["owner"]:
                owner = m.path.replace("/", ".")
                if owner != call["owner"] and not owner.endswith("." + call["owner"]):
                    continue
            # Check anchor and label because type variables can be erased in the anchor.
            for signature in (m.anchor, m.label):
                actual = _parameter_types(signature.partition("(")[2].removesuffix(")"))
                if actual is not None and len(actual) == len(wanted) and all(
                        a == w or a.endswith("." + w) for a, w in zip(actual, wanted)):
                    matches.append(m)
                    break
    return matches


def choose_contract(question, members, rank):
    """Rank full-text declarations under the same format-independent lookup policy."""
    explicit = _overloads(question, members)
    if explicit:
        return _rank(question, explicit, rank, detailed=True)
    candidates = _scope(question, members)
    # An owner plus an unparenthesized member name is a lookup across its overloads.
    if any(re.search(r"(?<![\w$])" + re.escape(m.owner) + r"(?![\w$])", question)
           for m in candidates):
        mentioned = [m for m in candidates if "(" in m.anchor and not m.anchor.startswith("<init>")
                     and re.search(r"(?<![\w$])" + re.escape(m.label.partition("(")[0]) +
                                   r"(?![\w$])", question)]
        if mentioned:
            candidates = mentioned
    member_kind = re.search(
        r"^(?:which|what)\b[^?.!]{0,90}?\b(member|method|constructor|constant|setting|"
        r"field|property|function|component|option)\b", question, re.I)
    scope = []
    if member_kind:
        kind = member_kind[1].lower()
        for candidate in candidates:
            if not candidate.anchor:
                continue
            if kind in {"method", "function"} and "(" not in candidate.anchor:
                continue
            if kind == "constructor" and not candidate.anchor.startswith("<init>("):
                continue
            if kind in {"constant", "setting", "option"} and "(" in candidate.anchor:
                continue
            scope.append(candidate)
    elif (re.search(r"\b(?:exception|error)\b", question, re.I)
          and re.search(r"\bthrow(?:n|s|ing)?\b", question, re.I)):
        scope = [m for m in candidates if m.anchor and re.search(
            r"\bthrows?\b", m.summary + " " + m.search_text, re.I)]
    return _rank(question, scope or candidates, rank, detailed=True)


_KIND_QUESTION = re.compile(
    r"\b(?:which|what)\b[^?.!]{0,72}?\b(member|method|constructor|constant|setting|"
    r"field|property|function|component|option|predicate)\b", re.I)
_DECLARATION_WORDS = {"a", "an", "can", "does", "for", "how", "in", "is", "on", "the",
                      "to", "what", "when", "which", "with"}


def _identifier_words(value):
    value = re.sub(r"([A-Z])([A-Z][a-z])", r"\1 \2", value)
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", value)
    return set(re.findall(r"[a-z0-9]+", value.lower())).difference(_DECLARATION_WORDS)


def _narrow_overloads(question, members):
    """Use terms that distinguish candidates, or leave an overloaded lookup unresolved."""
    evidence = [_identifier_words(m.anchor + " " + m.summary + " " + m.search_text)
                for m in members]
    shared = set.intersection(*evidence)
    query = _identifier_words(question)
    scores = [len(query.intersection(terms - shared)) for terms in evidence]
    best = max(scores)
    if best and scores.count(best) == 1:
        return members[scores.index(best)]
    return None


def _matches_kind(member, kind):
    if not member.anchor:
        return False
    constructor = member.anchor.startswith("<init>(")
    if kind == "constructor":
        return constructor
    if kind in {"method", "function", "predicate"}:
        return "(" in member.anchor and not constructor
    if kind in {"constant", "setting", "field", "option"}:
        return "(" not in member.anchor
    return True


def choose_declaration(question, members, rank):
    """Resolve declaration intent and require evidence before selecting an overload."""
    if not members:
        return None
    # Written method signatures constrain selection. Unsupported or ambiguous
    # signatures cannot safely fall back to an unrelated overload.
    declared_names = {m.label.partition("(")[0] for m in members if "(" in m.anchor}
    calls = [call for call in _CALL.finditer(question)
             if call["owner"] or call["name"] in declared_names]
    if calls:
        matches = list(dict.fromkeys(_overloads(question, members)))
        return matches[0] if len(matches) == 1 else None

    candidates = _scope(question, members)
    owners = {m.owner for m in candidates}
    qualified = {owner: re.findall(r"(?<![\w$])" + re.escape(owner) +
                                   rf"[.#]({_IDENTIFIER})(?![\w$.])", question)
                 for owner in owners}
    if any(qualified.values()):
        candidates = [m for m in candidates if m.anchor and
                      m.label.partition("(")[0] in qualified[m.owner]]
        if not candidates:
            return None

    named = [m for m in candidates if m.anchor and not m.anchor.startswith("<init>(")
             and re.search(r"(?<![\w$])" + re.escape(m.label.partition("(")[0])
                           + r"(?![\w$])", question)]
    if named and any(re.search(r"(?<![\w$])" + re.escape(m.owner) + r"(?![\w$])", question)
                     for m in named):
        candidates = named
    else:
        intent = _KIND_QUESTION.search(question)
        if intent:
            kind = intent[1].lower()
            matching = [m for m in candidates if _matches_kind(m, kind)]
            if matching:
                candidates = matching
        elif re.search(r"\b(?:exception|error)\b", question, re.I) and re.search(
                r"\bthrow(?:n|s|ing)?\b", question, re.I):
            throwing = [m for m in candidates if m.anchor and re.search(
                r"\bthrows?\b", m.summary + " " + m.search_text, re.I)]
            if throwing:
                candidates = throwing

    # API names can carry signal absent from the prose (for example, isEmpty).
    # Compare distinctive index terms alongside the published descriptions.
    entries = {m.path + "#" + m.anchor: m for m in candidates}
    descriptions = {key: f"{m.owner} {m.label} {m.summary} {m.search_text} "
                    + " ".join(sorted(_identifier_words(m.label.partition("(")[0])))
                    for key, m in entries.items()}
    selected = entries[rank(question, descriptions)[0]]
    if "(" in selected.anchor:
        family = [m for m in candidates if m.path == selected.path and "(" in m.anchor
                  and m.label.partition("(")[0] == selected.label.partition("(")[0]]
        if len(family) > 1:
            return _narrow_overloads(question, family)
    return selected


def _jdk_return_types(root):
    result = {}
    for section in root.iter("section"):
        anchor = section.get("id")
        if not anchor or "detail" not in section.get("class", "").split():
            continue
        for signature in section.iter("div"):
            if "member-signature" not in signature.get("class", "").split():
                continue
            return_type = next((span for span in signature.iter("span")
                                if "return-type" in span.get("class", "").split()), None)
            if return_type is not None:
                node = return_type.find("a")
                title = node.get("title", "") if node is not None else ""
                value = text(return_type)
                package = re.search(r"\bin ([\w.]+)$", title)
                if package and not value.startswith(package[1] + "."):
                    value = package[1] + "." + value
                result[anchor] = value
            break
    return result


def _jdk_details(root):
    """Map declaration anchors to complete local evidence with linked record component notes."""
    components = {}
    for dd in root.iter("dd"):
        for element in dd.iter():
            anchor = element.get("id", "")
            if anchor.startswith("param-"):
                components[anchor] = text(dd)
    details = {}
    for section in root.iter("section"):
        anchor = section.get("id")
        if anchor == "class-description":
            details[""] = text(section)
        elif anchor and "detail" in section.get("class", "").split():
            refs = dict.fromkeys(unquote(a.get("href", ""))[1:] for a in section.iter("a")
                                 if a.get("href", "").startswith("#param-"))
            details[anchor] = " ".join([text(section), *(components[ref] for ref in refs if ref in components)])
    return details


def enrich_jdk_members(site, members, *, include_return_types=False):
    """Index each public type page once. Preserve published index descriptions and ordering."""
    by_path = defaultdict(list)
    for i, member in enumerate(members):
        by_path[member.path].append((i, member))
    result = list(members)
    for path, grouped in by_path.items():
        source = (site / (path + ".html")).read_text()
        root = Tree(source).root
        details = _jdk_details(root)
        returns = _jdk_return_types(root) if include_return_types else {}
        for i, member in grouped:
            result[i] = replace(member, search_text=details.get(member.anchor, ""),
                                return_type=returns.get(member.anchor, ""))
    return result


def member_passage(source, fmt, member):
    """Fetch a single declaration, falling back to its published index description."""
    if fmt == "html":
        detail = _jdk_details(Tree(source).root).get(member.anchor)
        if detail:
            return detail if member.anchor else f"{member.owner}: {detail}"
    elif fmt == "markdown":
        if not member.anchor:
            # Include the type description, signature, and component contracts, ahead of its members.
            body = re.sub(r"\A---\n.*?\n---\n", "", source, flags=re.S).strip()
            body = re.sub(r"^## API at a glance\n\n(?:- [^\n]+\n)+", "", body, flags=re.M)
            return re.split(r"^## ", body, maxsplit=1, flags=re.M)[0].strip()
        # The short contract summary is bounded. Every declaration also has a full section.
        quick = list(glance_rows(source, member.path))
        for entry, row in quick:
            if entry.anchor == member.anchor:
                return "- " + row
        sections = [s[0].strip() for s in re.finditer(r"^### ([^\n]+)\n(.*?)(?=^#{1,3} |\Z)", source, re.M | re.S)
                    if _plain_markdown(s[1]) == member.label]
        # Members outside the bounded summary can still share a heading with a listed overload.
        unlinked = []
        anchored = None
        for section in sections:
            anchor = re.match(r'^### [^\n]+\n<a id="([^"]+)"></a>', section)
            if anchor is None:
                unlinked.append(section)
            elif unescape(anchor[1]) == member.anchor:
                anchored = section
        if len(sections) > 1:
            # Distinct overloads can share the same display signature (package-local type names).
            # The URL's index description is unambiguous even if headings collide.
            if anchored:
                return anchored
            matching = [s for s in unlinked if member.summary and member.summary in _plain_markdown(s)]
            if len(matching) == 1:
                return matching[0]
        else:
            for entry, row in quick:
                if not entry.anchor and entry.label == member.label:
                    return "- " + row
            if anchored or unlinked:
                return anchored or unlinked[0]
    return f"{member.label}: {member.summary}"
