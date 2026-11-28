"""Local HTML-start routes with observable document reads and no model calls."""
from dataclasses import asdict
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re
from urllib.parse import quote, unquote, urldefrag, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener

from eval_discovery import Member, Tree, choose_contract, enrich_jdk_members, load_members, member_passage
from eval_evidence import (extract_adaptive_answer, markdown_detail, markdown_member_passage,
                           markdown_members, needs_detail, select_evidence)
from eval_extract import extract_contract_answer
from eval_rank import rank, rank_contract
from query_docs import contract_passage


ROUTES = ("javadoc", "inline", "contracts", "direct", "javadoc-adaptive", "javadoc-lazy", "adaptive")


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _Sources:
    def __init__(self, entry, encode):
        self.entry = urldefrag(str(entry))[0]
        self.origin = urlsplit(self.entry)
        if self.origin.scheme == "file" and not self.origin.netloc:
            self.root = Path(unquote(self.origin.path)).resolve().parent
        elif (self.origin.scheme == "http" and not self.origin.username
              and self.origin.hostname and (
                  self.origin.hostname == "localhost" or _is_loopback(self.origin.hostname))):
            self.root = None
        else:
            raise ValueError("Use an HTML file URL or a loopback HTTP URL")
        self.encode = encode
        self.requests = []
        self.cache = {}
        self.opener = build_opener(ProxyHandler({}), _NoRedirect())

    def read(self, url, stage="discovery"):
        url = urldefrag(url)[0]
        if url in self.cache:
            return self.cache[url]
        target = urlsplit(url)
        if (target.scheme, target.netloc) != (self.origin.scheme, self.origin.netloc):
            raise ValueError(f"Document URL escaped local origin: {url}")
        if self.root is not None:
            path = Path(unquote(target.path)).resolve()
            if not path.is_relative_to(self.root):
                raise ValueError(f"Document URL escaped fixture directory: {url}")
            data = path.read_bytes()
        else:
            with self.opener.open(url, timeout=10) as response:
                data = response.read()
        body = data.decode("utf-8")
        display = str(path.relative_to(self.root)) if self.root is not None else target.path
        self.requests.append(dict(path=display, stage=stage, bytes=len(data),
                                  tokens=len(self.encode(body)) if self.encode else None))
        self.cache[url] = body
        return body


def _is_loopback(host):
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


class _Index:
    """Expose fetched source through the same read interface as the local indexer."""

    def __init__(self, sources, base, index=None):
        self.sources = sources
        self.base = base
        self.index = index

    def __truediv__(self, path):
        if self.index and path in {"index-all.html", "index-all.md"}:
            return _Document(self.sources, self.index)
        return _Document(self.sources, urljoin(self.base, quote(path, safe="/")))


class _Document:
    def __init__(self, sources, url):
        self.sources = sources
        self.url = url

    def read_text(self):
        return self.sources.read(self.url)


def _link(root, basename=None, relation=None, mime=None):
    for tag in root.iter():
        if tag.tag not in {"a", "link"} or not tag.get("href"):
            continue
        href = tag.get("href")
        if basename and PurePosixPath(unquote(urlsplit(href).path)).name != basename:
            continue
        if relation and relation not in tag.get("rel", "").split():
            continue
        if mime and tag.get("type") != mime:
            continue
        return href
    return None


def _markdown_links(index):
    return [(m[1], m[2], m[0]) for m in
            re.finditer(r"^- \[([^\n]+?)\]\(([^)\n]+)\)[^\n]*", index, re.M)]


def _require(href, what):
    if not href:
        raise ValueError(f"No {what} link was advertised")
    return href


def _cards(index):
    for line in index.splitlines():
        item = json.loads(line)
        link = urlsplit(item["href"])
        path = unquote(link.path).removesuffix(".html")
        anchor = unquote(link.fragment)
        label = item["name"]
        if "(" in anchor:
            label += "(" + anchor.split("(", 1)[1]
        yield Member(path, anchor, label, item.get("summary", ""), contract_passage(label, item)), item


def _choose(question, members, adaptive):
    if adaptive:
        from eval_discovery import choose_declaration
        return choose_declaration(question, members, rank_contract)
    return choose_contract(question, members, rank_contract)


def _javadoc(sources, entry, root, question, adaptive=False, lazy=False):
    href = _link(root, "index-all.html")
    if not href:
        canonical = _link(root, relation="canonical")
        if canonical:
            entry = urljoin(entry, canonical)
            root = Tree(sources.read(entry)).root
            href = _link(root, "index-all.html")
    index = urljoin(entry, _require(href, "Javadoc alphabetical index"))
    site = _Index(sources, index, index)
    members = load_members(site, "html")
    if lazy:
        member = _choose(question, members, adaptive)
        if member is None:
            return None, ""
        page = sources.read(urljoin(index, quote(member.path + ".html", safe="/")), "content")
        return member, member_passage(page, "html", member)
    members = enrich_jdk_members(site, members)
    member = _choose(question, members, adaptive)
    excerpt = (member.search_text or (member.label + ": " + member.summary)) if member else ""
    return member, excerpt


def _inline(sources, llms_url, llms, question):
    links = {href: line for title, href, line in _markdown_links(llms)
             if href.endswith(".md") and re.search(r" \([^()\n]+ in [^()\n]+\)$", title)}
    target = rank(question, links)[0]
    path = unquote(urlsplit(target).path).removesuffix(".md")
    source = sources.read(urljoin(llms_url, target), "content")
    members = [Member(path, "", PurePosixPath(path).name, "")]
    for title, anchor, block in markdown_members(source):
        label = title.replace("\\", "")
        anchored = bool(anchor)
        anchor = anchor or label
        item = Member(path, anchor, label, "")
        if anchored:
            passage = markdown_member_passage(label, block)
        else:
            passage = member_passage(source, "markdown", item)
        members.append(Member(path, anchor, label, "", passage))
    member = choose_contract(question, members, rank_contract)
    excerpt = member.search_text or member_passage(source, "markdown", member)
    return member, excerpt


def route(entry_url, question, variant, *, chars=600, encode=None):
    """Follow a documented route from a local HTML page under a shared excerpt limit."""
    if variant not in ROUTES or chars <= 0:
        raise ValueError("Choose a known route and a positive excerpt length")
    sources = _Sources(entry_url, encode)
    entry = sources.entry
    root = Tree(sources.read(entry)).root
    # Older HTML also marks the LLM indexes as Markdown alternates.
    markdown_alternates = [tag.get("href") for tag in root.iter("link")
                           if tag.get("type") == "text/markdown"
                           and "alternate" in tag.get("rel", "").split()
                           and tag.get("href")
                           and urlsplit(tag.get("href")).path.endswith(".md")]
    twin = markdown_alternates[0] if len(markdown_alternates) == 1 else None
    adaptive = variant in {"javadoc-adaptive", "javadoc-lazy", "adaptive"}
    if variant in {"javadoc", "javadoc-adaptive", "javadoc-lazy"}:
        member, passage = _javadoc(sources, entry, root, question, adaptive, lazy=variant == "javadoc-lazy")
    elif variant in {"direct", "adaptive"}:
        href = _require(_link(root, "contracts.ndjson", relation="describedby"), "HTML contracts")
        cards_url = urljoin(entry, href)
        cards = dict(_cards(sources.read(cards_url)))
        member = _choose(question, list(cards), adaptive)
        passage = member.search_text if member else ""
        if adaptive and member:
            packet = select_evidence(question, member, passage, chars)
            if needs_detail(question, member, packet):
                target = urljoin(cards_url, quote(member.path + ".md", safe="/"))
                detail = markdown_detail(sources.read(target, "content"), member, cards[member])
                if detail:
                    passage = detail
    else:
        llms_url = urljoin(entry, _require(_link(root, "llms.txt"), "HTML llms.txt"))
        llms = sources.read(llms_url)
        if variant == "inline":
            member, passage = _inline(sources, llms_url, llms, question)
        else:
            href = next((href for _, href, _ in _markdown_links(llms)
                         if PurePosixPath(urlsplit(href).path).name == "contracts.ndjson"), None)
            cards = [member for member, _ in _cards(sources.read(
                urljoin(llms_url, _require(href, "llms.txt contracts"))))]
            member = choose_contract(question, cards, rank_contract)
            passage = member.search_text
    excerpt = select_evidence(question, member, passage, chars) if adaptive and member else passage[:chars]
    extract = extract_adaptive_answer if adaptive else extract_contract_answer
    answer = extract(question, excerpt, member.owner) if member and (adaptive or len(passage) <= chars) else None
    requests = sources.requests
    return dict(page=member.owner if member else None, path=member.path if member else None,
                anchor=member.anchor if member else None, excerpt=excerpt,
                extraction=asdict(answer) if answer else None,
                fetches=len(requests), bytes=sum(r["bytes"] for r in requests), requests=requests,
                discovery_tokens=sum(r["tokens"] for r in requests if r["stage"] == "discovery") if encode else None,
                source_tokens=sum(r["tokens"] for r in requests) if encode else None,
                excerpt_tokens=len(encode(excerpt)) if encode else None,
                markdown_url=urljoin(entry, twin) if twin else None)
