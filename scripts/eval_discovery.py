"""Look up both arms through their published alphabetical declaration indexes."""
from dataclasses import dataclass
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
    owners = {m.owner for m in members}
    named = [owner for owner in owners
             if re.search(r"(?<![\w$])" + re.escape(owner) + r"(?![\w$])", question)]
    # Match the most specific nested name in Planner.Options.Detail, retaining other owners.
    named = [owner for owner in named if not any(other.startswith(owner + ".") for other in named)]
    candidates = [m for m in members if m.owner in named] if named else members
    qualified = [m for m in candidates if m.anchor and re.search(
        r"(?<![\w$])" + re.escape(m.owner + "." + m.label.split("(", 1)[0]) + r"(?![\w$])", question)]
    if qualified:
        candidates = qualified
    # Use the full path and JVM signature for ties between identically named overloads.
    entries = {m.path + "#" + m.anchor: m for m in candidates}
    descriptions = {key: f"{m.owner} {m.label} {m.summary}" for key, m in entries.items()}
    return entries[rank(question, descriptions)[0]]


def member_passage(source, fmt, member):
    """Fetch a single declaration, falling back to its published index description."""
    if fmt == "html":
        root = Tree(source).root
        if not member.anchor:
            header = root.find(".//section[@id='class-description']")
            return f"{member.owner}: {text(header) or member.summary}"
        section = next((el for el in root.iter("section") if el.get("id") == member.anchor), None)
        if section is not None:
            # The JDK links generated accessor docs to the record component's own definition.
            refs = {unquote(a.get("href", ""))[1:] for a in section.iter("a")
                    if a.get("href", "").startswith("#param-")}
            component_docs = [text(dd) for dd in root.iter("dd")
                              if any(el.get("id") in refs for el in dd.iter())]
            return " ".join([text(section), *component_docs]).strip()
    elif fmt == "markdown":
        if not member.anchor:
            # Include the type description, signature, and component contracts, ahead of its members.
            body = re.sub(r"\A---\n.*?\n---\n", "", source, flags=re.S).strip()
            body = re.sub(r"^## API at a glance\n\n(?:- [^\n]+\n)+", "", body, flags=re.M)
            return re.split(r"^## ", body, maxsplit=1, flags=re.M)[0].strip()
        # The short contract summary is bounded. Every declaration also has a full section.
        quick = re.search(r"^## API at a glance\n\n((?:- [^\n]+\n)+)", source, re.M)
        sections = [s[0].strip() for s in re.finditer(r"^### ([^\n]+)\n(.*?)(?=^#{1,3} |\Z)", source, re.M | re.S)
                    if _plain_markdown(s[1]) == member.label]
        if len(sections) > 1:
            # Distinct overloads can share the same display signature (package-local type names).
            # The URL's index description is unambiguous even if headings collide.
            matching = [s for s in sections if member.summary and member.summary in _plain_markdown(s)]
            if len(matching) == 1:
                return matching[0]
        else:
            if quick:
                for row in quick[1].splitlines():
                    if row[2:].partition(": ")[0] == member.label:
                        return row
            if sections:
                return sections[0]
    return f"{member.label}: {member.summary}"
