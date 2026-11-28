"""Bounded, model-directed browsing of published static documentation.

Navigation has no access to the question or a reference answer. A session fetches
one file on first use and exposes only links and citations in displayed windows.
"""
import json
import re
from dataclasses import dataclass
from html import unescape
from pathlib import Path, PurePosixPath
from urllib.parse import quote, unquote, urldefrag, urljoin, urlsplit

from eval_discovery import Tree


ORIGIN = "https://static.invalid/"
BLOCK = set("address article aside blockquote body dd details div dl dt footer h1 h2 h3 h4 h5 h6 header html li main nav ol p pre section table tbody td th thead tr ul".split())
HIDDEN = {"script", "style", "template", "svg", "noscript"}
MD_LINK = re.compile(r"\[([^\]\n]*)\]\((<?((?:[^()\s<>]|\([^()\n]*\))+)>?)(?:\s+\"[^\"]*\")?\)")
MD_ID = re.compile(r'<a\s+(?:name|id)="([^"]+)"\s*>\s*</a>', re.I)


@dataclass(frozen=True)
class Row:
    text: str
    destination: str
    links: tuple


def _plain(text):
    return " ".join(text.split())


def _resolve(page, href):
    if not href or any(c in href for c in "\x00\r\n\\"):
        return None
    base = urljoin(ORIGIN, page)
    url = urljoin(base, href)
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.netloc != "static.invalid" or parts.query:
        return None
    path = unquote(parts.path.lstrip("/"))
    if "\\" in path or "\x00" in path or path.startswith("/") or ".." in PurePosixPath(path).parts:
        return None
    if not path or path.endswith("/"):
        path += "index.html"
    suffix = PurePosixPath(path).suffix.lower()
    if suffix not in (".html", ".htm", ".md", ".txt", ".json", ".ndjson", ".js"):
        return None
    return quote(path, safe="/.$-_,()") + ("#" + parts.fragment if parts.fragment else "")


class Navigator:
    def __init__(self, root, window_chars=2200):
        if window_chars < 160:
            raise ValueError("A navigation window requires at least 160 characters")
        self.root = Path(root).resolve()
        self.entry = "index.html"
        if self.root.is_file():
            self.entry = self.root.name
            self.root = self.root.parent
        self.window_chars = window_chars
        self.requests = []
        self._docs = {}
        self._anchors = {}
        self._links = {}
        self._urls = {}
        self._visible = set()
        self._evidence = {}
        self._view = 0
        self._page = None
        self._next = 0
        self._last_find = None

    @property
    def bytes(self):
        return sum(r["bytes"] for r in self.requests)

    def citation(self, evidence_id):
        if type(evidence_id) is not int or evidence_id not in self._evidence:
            return None
        return self._evidence[evidence_id].copy()

    def _link(self, page, href):
        target = _resolve(page, href)
        if not target:
            return "", None
        if target not in self._urls:
            number = len(self._urls) + 1
            self._urls[target] = number
            self._links[number] = target
        number = self._urls[target]
        return f"[L{number}]", number

    def _html(self, page, body, emit, mark):
        root = Tree(body).root
        redirect = None
        for el in root.iter("meta"):
            if el.get("http-equiv", "").casefold() == "refresh":
                match = re.fullmatch(r"\s*0(?:\.0+)?\s*;\s*(?:url\s*=\s*)?(.+?)\s*", el.get("content", ""), re.I)
                if match:
                    redirect = _resolve(page, match[1].strip("'\042"))
        for el in root.iter("head"):
            for child in el:
                if child.tag == "title":
                    emit("".join(child.itertext()), page)
                elif child.tag == "link" and set(child.get("rel", "").split()) & {"alternate", "canonical", "help", "describedby", "search", "index"}:
                    href = child.get("href", "")
                    marker, num = self._link(page, href)
                    if num:
                        label = " ".join(filter(None, [child.get("rel"), child.get("type"), child.get("title")]))
                        emit(f"{marker} {href} {label}", page, [num])
                elif child.tag == "script" and PurePosixPath(child.get("src", "")).name in {"script.js", "search-page.js", "search.js"}:
                    href = child.get("src")
                    marker, num = self._link(page, href)
                    if num:
                        emit(f"{marker} script {href}", page, [num])

        def inline(node, links):
            if node.tag in HIDDEN or node.get("aria-hidden") == "true" or "hidden" in node.attrib:
                return ""
            content = (node.text or "") + "".join(inline(ch, links) + (ch.tail or "") for ch in node)
            if node.tag == "a":
                href = node.get("href", "")
                marker, number = self._link(page, href)
                if number:
                    links.append(number)
                    return f"{content.strip() or node.get('title') or href} {marker} "
            return (" " + content + " ") if node.tag in BLOCK else content

        def destination(node):
            link = next((a for a in node.iter("a") if "member-name-link" in a.get("class", "").split()
                         or "type-name-link" in a.get("class", "").split()), None)
            return _resolve(page, link.get("href")) if link is not None else None

        def output(nodes, target):
            links = []
            content = _plain(" ".join(inline(n, links) for n in nodes))
            if content:
                emit(content, target, links)

        def walk(el, target):
            if el.tag in HIDDEN or el.get("aria-hidden") == "true" or "hidden" in el.attrib:
                return
            css = el.get("class", "").split()
            member = next((c.get("id") for c in el if "member-head" in c.get("class", "").split()), None)
            if "detail" in css and el.get("id"):
                member = el.get("id")
            if el.get("id"):
                mark(el.get("id"))
            if member:
                mark(member)
                output([el], page + "#" + quote(member))
                return
            if el.tag == "dl" and "index" in css:
                nodes = list(el)
                for i, term in enumerate(nodes):
                    if term.tag == "dt":
                        desc = nodes[i+1:i+2] if i+1 < len(nodes) and nodes[i+1].tag == "dd" else []
                        output([term] + desc, destination(term) or target)
                return
            if "summary-table" in css:
                cells = []
                for child in el:
                    cells.append(child)
                    if "col-last" in child.get("class", "").split():
                        pointer = next((dest for cell in cells if (dest := destination(cell))), None)
                        output(cells, pointer or target)
                        cells = []
                if cells:
                    output(cells, target)
                return
            if el.tag in {"h1","h2","h3","h4","h5","h6","p","pre","a","dt","dd","tr"} or not any(c.tag in BLOCK for c in el):
                output([el], target)
                return
            if el.text and el.text.strip():
                emit(el.text, target)
            for child in el:
                walk(child, target)
                if child.tail and child.tail.strip():
                    emit(child.tail, target)

        for el in root.iter("body"):
            walk(el, page)
        return redirect

    def _text(self, page, body, emit, mark):
        target = page
        fenced = None
        for line in body.splitlines():
            if not line.strip():
                continue
            if PurePosixPath(page).suffix == ".ndjson":
                try:
                    obj = json.loads(line)
                except ValueError:
                    obj = {}
                href = obj.get("href") if isinstance(obj, dict) else None
                marker, num = self._link(page, href) if isinstance(href, str) else ("", None)
                emit(f"{marker} {line}", _resolve(page, href) if num else page, [num] if num else [])
                continue
            fence = re.match(r"^\s*(`{3,}|~{3,})", line)
            if fence and (not fenced or fence[1][0] == fenced[0] and len(fence[1]) >= len(fenced)):
                fenced = None if fenced else fence[1]
                emit(line, target)
                continue
            if fenced:
                emit(line, target)
                continue
            if line.startswith("#"):
                target = page
            anchor = MD_ID.search(line)
            if anchor:
                mark(unescape(anchor[1]))
                target = page + "#" + quote(unescape(anchor[1]))
                emit("#" + unescape(anchor[1]), target)
                continue
            links = []
            targets = []

            def replace(match):
                href = match[3]
                marker, number = self._link(page, href)
                if number:
                    links.append(number)
                    targets.append(self._links[number])
                    return match[0] + " " + marker
                return match[0]

            shown = MD_LINK.sub(replace, line)
            pointer = targets[0] if target == page and line.lstrip().startswith(("- [", "* [")) and targets else target
            emit(shown, pointer, links)

    def _js(self, page, body, emit):
        # Native Javadoc publishes its actual search indexes as a JS assignment
        # and builds link destinations from the fields of each index entry.
        data = re.match(r"\s*(type|member|package|module|tag)SearchIndex\s*=\s*(\[.*\])\s*;", body, re.S)
        if data:
            for item in json.loads(data[2]):
                label = item.get("l", "")
                package = (item.get("p", "").replace(".", "/") + "/") if item.get("p") else ""
                module = item.get("m", "") + "/" if item.get("m") else ""
                href = item.get("u")
                if data[1] == "member":
                    href = module + package + item.get("c", "") + ".html#" + (href or label)
                elif data[1] == "type":
                    href = href or module + package + label + ".html"
                elif data[1] == "package":
                    href = href or module + label.replace(".", "/") + "/package-summary.html"
                elif data[1] == "module":
                    href = href or label + "/module-summary.html"
                marker, num = self._link(page, href) if href else ("", None)
                emit(" ".join(filter(None, (marker, item.get("p"), item.get("c"), label, href))),
                     self._links[num] if num else page, [num] if num else [])
            return
        for line in body.splitlines():
            if ref := re.search(r"""createElem\(doc,\s*tag,\s*['"]([^'"]+\.js)['"]\)""", line):
                # This loader assigns pathtoroot + path, independent of script location.
                marker, num = self._link(self.entry, ref[1])
                emit(f"{line.strip()} {marker}", page, [num] if num else [])
            else:
                emit(line, page)

    def _load(self, page):
        if page in self._docs:
            return self._docs[page]
        resolved = (self.root / unquote(page)).resolve()
        if not resolved.is_relative_to(self.root) or not resolved.is_file():
            raise ValueError("Published link is unavailable in this site")
        source = resolved.read_bytes()
        self.requests.append({"path": page, "bytes": len(source)})
        body = source.decode("utf-8", errors="replace")
        rows = []
        anchors = self._anchors[page] = {}

        def mark(anchor):
            anchors.setdefault(anchor, len(rows))

        def emit(content, target, links=()):
            content = _plain(content)
            # Split oversized records into observable text windows. A citation
            # can vouch only for the displayed fragment, even if its record is long.
            size = max(80, self.window_chars - 85)
            while content:
                stop = min(len(content), size)
                if stop < len(content):
                    cut = content.rfind(" ", 0, stop + 1)
                    if cut > size // 2:
                        stop = cut
                chunk, content = content[:stop], content[stop:].lstrip()
                visible = tuple(i for i in links if f"[L{i}]" in chunk)
                rows.append(Row(chunk, target or page, visible))

        redirect = None
        if page.endswith((".html", ".htm")):
            redirect = self._html(page, body, emit, mark)
        elif page.endswith(".js"):
            self._js(page, body, emit)
        else:
            self._text(page, body, emit, mark)
        self._docs[page] = (rows, redirect)
        return rows, redirect

    def start(self):
        return self._open(self.entry)

    def _open(self, url, query=None):
        checked = set()
        while True:
            page, anchor = urldefrag(url)
            if page in checked:
                raise ValueError("Circular redirect")
            checked.add(page)
            rows, redirect = self._load(page)
            if not redirect:
                break
            url = redirect
        self._page = page
        self._last_find = None
        offset = self._anchors[page].get(unquote(anchor), 0) if anchor else 0
        if query:
            return self._find(query)
        return self._window(offset)

    def _window(self, offset, error=None):
        rows, _ = self._load(self._page)
        self._view += 1
        ids, lines = [], []
        start = offset
        used = 0
        while offset < len(rows) and error is None:
            row = rows[offset]
            number = len(self._evidence) + 1
            line = f"[E{number}] {row.text}"
            if used + len(line) + 1 > self.window_chars - 60:
                break
            path, anchor = urldefrag(row.destination)
            self._evidence[number] = {"page": PurePosixPath(unquote(path)).stem,
                                      "path": str(PurePosixPath(unquote(path)).with_suffix("")),
                                      "anchor": unquote(anchor), "excerpt": row.text}
            self._visible.update(row.links)
            ids.append(number)
            lines.append(line)
            used += len(line) + 1
            offset += 1
        if not error:
            self._next = offset
        more = self._next < len(rows)
        header = f"{self._page} rows {start + 1}–{offset}/{len(rows)}" if lines else self._page
        body = "".join("\n" + line for line in lines)
        if error:
            body += "\n" + error
        if more:
            body += "\n[next]"
        room = self.window_chars - len(body)
        if len(header) > room:
            header = "…" + header[-(room - 1):]
        text = header + body
        return {"text": text, "url": self._page, "more": more, "view": self._view, "evidence_ids": ids}

    def _find(self, query):
        if not isinstance(query, str) or not query.strip():
            raise ValueError("Find requires a nonempty literal")
        key = query.strip().casefold()
        offset = self._last_find[1] + 1 if self._last_find and self._last_find[0] == key else 0
        rows, _ = self._load(self._page)
        for i in range(offset, len(rows)):
            if key in rows[i].text.casefold():
                self._last_find = (key, i)
                return self._window(i)
        return self._window(offset, "No more literal matches in this document.")

    def act(self, action):
        if self._page is None:
            raise ValueError("Start navigation first")
        kind = action.get("action")
        if kind == "open":
            link = action.get("link")
            if type(link) is not int or link not in self._visible:
                raise ValueError("Open requires a link ID shown in a view")
            return self._open(self._links[link], action.get("query"))
        if kind == "find":
            return self._find(action.get("query"))
        if kind == "next":
            return self._window(self._next)
        raise ValueError("Unknown navigation action")
