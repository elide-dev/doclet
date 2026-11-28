"""Local HTML-start routes with observable document reads and no model calls."""
from dataclasses import asdict
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re
from urllib.parse import quote, unquote, urldefrag, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener

from declaration_guard import contradicts_description, description_overlap
from eval_discovery import (Member, Tree, _CALL, _KIND_QUESTION, _jdk_details, _matches_kind,
                            _overloads, _scope, choose_contract, enrich_jdk_members,
                            load_members, member_passage)
from eval_evidence import (_preferences, extract_adaptive_answer, markdown_detail, markdown_member_passage,
                           markdown_members, needs_detail, select_evidence)
from eval_extract import extract_contract_answer
from eval_rank import rank, rank_contract
from eval_return_type import prefer_return_type, requested_return_type
from query_docs import contract_passage


ROUTES = ("javadoc", "inline", "contracts", "direct", "javadoc-adaptive", "javadoc-lazy", "javadoc-scoped", "adaptive", "scoped")


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _Sources:
    def __init__(self, entry, encode, *, allow_https=False, file_root=None):
        self.entry = urldefrag(str(entry))[0]
        self.origin = urlsplit(self.entry)
        if self.origin.scheme == "file" and not self.origin.netloc:
            self.root = (Path(file_root).resolve() if file_root is not None
                         else Path(unquote(self.origin.path)).resolve().parent)
        elif self.origin.hostname and self.origin.username is None and (
            (allow_https and self.origin.scheme == "https")
            or (self.origin.scheme == "http" and (
                self.origin.hostname == "localhost" or _is_loopback(self.origin.hostname)))
        ):
            self.root = None
        else:
            protocols = "an HTML file URL, loopback HTTP URL, or HTTPS URL" if allow_https else (
                "an HTML file URL or loopback HTTP URL")
            raise ValueError(f"Use {protocols}")
        self.encode = encode
        self.requests = []
        self.cache = {}
        self.declarations = {}
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
        returns = item.get("returns") or {}
        yield Member(path, anchor, label, item.get("summary", ""), contract_passage(label, item),
                     returns.get("type", "")), item


def _choose(question, members, adaptive):
    if adaptive:
        from eval_discovery import choose_declaration
        return choose_declaration(question, members, rank_contract)
    return choose_contract(question, members, rank_contract)


def _javadoc_index(sources, entry, root):
    href = _link(root, "index-all.html")
    if not href:
        canonical = _link(root, relation="canonical")
        if canonical:
            entry = urljoin(entry, canonical)
            root = Tree(sources.read(entry)).root
            href = _link(root, "index-all.html")
    index = urljoin(entry, _require(href, "Javadoc alphabetical index"))
    return index, _Index(sources, index, index)


def _javadoc(sources, entry, root, question, adaptive=False, lazy=False, return_types=False):
    index, site = _javadoc_index(sources, entry, root)
    if lazy:
        members = load_members(site, "html")
        member = _choose(question, members, adaptive)
        if member is None:
            return None, ""
        page = sources.read(urljoin(index, quote(member.path + ".html", safe="/")), "content")
        return member, member_passage(page, "html", member)
    members = _javadoc_members(sources, index, site, return_types)
    member = _choose(question, members, adaptive)
    excerpt = (member.search_text or (member.label + ": " + member.summary)) if member else ""
    return member, excerpt


def _javadoc_members(sources, index, site, return_types=False):
    key = (index, return_types)
    if key not in sources.declarations:
        sources.declarations[key] = enrich_jdk_members(
            site, load_members(site, "html"), include_return_types=return_types)
    return sources.declarations[key]


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


def _named_type(question, paths):
    """Resolve one explicit owner, using its full name where supplied."""
    matches = []
    for path in paths:
        owner = PurePosixPath(path).name
        qualified = path.replace("/", ".")
        for name in dict.fromkeys((qualified, owner)):
            for match in re.finditer(r"(?<![\w$])" + re.escape(name) + r"(?![\w$])", question):
                explicit_member = bool(re.match(r"[.#][A-Za-z_$][\w$]*\b", question[match.end():]))
                matches.append((path, match.start(), match.end(), explicit_member))
    # Within the same textual mention prefer the most specific nested/qualified owner.
    matches = [m for m in matches if not any(
        other[1] <= m[1] and other[2] >= m[2]
        and (other[1], other[2]) != (m[1], m[2]) for other in matches)]
    qualified = {m[0] for m in matches if m[3]}
    owners = qualified or {m[0] for m in matches}
    return next(iter(owners)) if len(owners) == 1 else None


def _javadoc_native_types(sources, entry, root, base):
    """Read the index advertised by the JDK's loader without executing the script."""
    scripts = [tag.get("src", "") for tag in root.iter("script")]
    src = next((script for script in scripts if PurePosixPath(urlsplit(script).path).name == "script.js"), "")
    if not src:
        return {}
    loader = sources.read(urljoin(entry, src))
    hint = re.search(r"""["'](type-search-index\.js)["']""", loader)
    if not hint:
        return {}
    source = sources.read(urljoin(base, hint[1]))
    assignment = re.match(r"\s*typeSearchIndex\s*=\s*", source)
    if not assignment:
        return {}
    records, _ = json.JSONDecoder().raw_decode(source, assignment.end())
    paths = {}
    for record in records:
        # Module paths require the module's navigation context. The member index
        # already carries those paths, so delegate when the index is modular.
        if record.get("m"):
            return {}
        if "p" in record and not record.get("u") and re.fullmatch(r"[\w$.]+", record.get("l", "")):
            package = record["p"].replace(".", "/") if record["p"] != "<Unnamed>" else ""
            path = "/".join(filter(None, (package, record["l"])))
            paths[path] = urljoin(base, quote(path + ".html", safe="/"))
    return paths


def _javadoc_local_types(entry, root, base):
    pages = {}
    for cell in root.iter("div"):
        if not {"class-summary", "col-first"}.issubset(cell.get("class", "").split()):
            continue
        link = cell.find("a")
        if link is None:
            continue
        target = urljoin(entry, link.get("href", ""))
        if target.startswith(base) and target.endswith(".html"):
            pages[unquote(target[len(base):]).removesuffix(".html")] = target
    if any("class-declaration-page" in tag.get("class", "").split() for tag in root.iter("body")):
        if entry.startswith(base) and entry.endswith(".html"):
            pages[unquote(entry[len(base):]).removesuffix(".html")] = entry
    return pages


def _javadoc_scoped(sources, entry, root, question):
    """Try an exact owner on standard Javadoc's native links and type search index."""
    if not _link(root, "index-all.html"):
        canonical = _link(root, relation="canonical")
        if canonical:
            entry = urljoin(entry, canonical)
            root = Tree(sources.read(entry)).root
    index = _link(root, "index-all.html")
    if not index:
        return _javadoc(sources, entry, root, question, adaptive=True, lazy=True)

    base = urljoin(urljoin(entry, index), ".")
    pages = _javadoc_local_types(entry, root, base)
    owner = _named_type(question, pages)
    # Without a Java-style type mention, the type-only index cannot help.
    possible_names = set(re.findall(r"(?<![\w$])[A-Z][\w$]*", question))
    ordinary_openers = {"Can", "Does", "How", "Is", "On", "Should", "What", "When", "Where", "Which", "Why"}
    if not owner and (possible_names - ordinary_openers):
        pages = _javadoc_native_types(sources, entry, root, base)
        owner = _named_type(question, pages)
    if owner:
        page = Tree(sources.read(pages[owner], "content")).root
        details = _jdk_details(page)
        name = PurePosixPath(owner).name
        members = [Member(owner, anchor, anchor.replace("<init>", name.rsplit(".", 1)[-1], 1)
                          if anchor else name, "", passage)
                   for anchor, passage in details.items()]
        member = _choose(question, members, adaptive=True)
        if member:
            return member, member.search_text
    return _javadoc(sources, entry, root, question, adaptive=True, lazy=True)


def _scoped(sources, entry, root, question, chars):
    root_hint = next((tag.get("data-root") for tag in root.iter("html") if tag.get("data-root")), "./")
    base = urljoin(entry, root_hint)
    pages = {}
    for tag in root.iter("a"):
        if not {"nav-type", "decl"}.intersection(tag.get("class", "").split()):
            continue
        target = urljoin(entry, tag.get("href", ""))
        if target.startswith(base) and target.endswith(".html"):
            path = unquote(target[len(base):]).removesuffix(".html")
            pages[path] = target.removesuffix(".html") + ".md"
    owner = _named_type(question, pages)
    if not owner and not _link(root, "contracts.ndjson", relation="describedby"):
        href = _link(root, "index-all.md", relation="index")
        if href:
            index = urljoin(entry, href)
            site = _Index(sources, index, index)
            pages = {m.path: urljoin(index, quote(m.path + ".md", safe="/"))
                     for m in load_members(site, "markdown") if not m.anchor}
            owner = _named_type(question, pages)
    if not owner:
        return None
    body = sources.read(pages[owner], "content")
    type_record = Member(owner, "", PurePosixPath(owner).name, "")
    type_text = member_passage(body, "markdown", type_record)
    members = [Member(owner, "", type_record.label, "", type_text)]
    for title, anchor, block in markdown_members(body):
        if not anchor:
            return None  # Older pages need the published index to distinguish overloads.
        label = title.replace("\\", "")
        passage = markdown_member_passage(label, block, normalize_contracts=True)
        members.append(Member(owner, anchor, label, "", passage))
    member = _choose(question, members, adaptive=True)
    # The complete type page can settle a scoped question without a second index.
    if member and member.search_text:
        return member, member.search_text
    return None


def _legacy_contracts_url(sources, entry, root):
    llms_url = urljoin(entry, _require(_link(root, "llms.txt"), "HTML llms.txt"))
    llms = sources.read(llms_url)
    href = next((href for _, href, _ in _markdown_links(llms)
                 if PurePosixPath(urlsplit(href).path).name == "contracts.ndjson"), None)
    return urljoin(llms_url, _require(href, "llms.txt contracts"))


def _contracts_url(sources, entry, root, allow_legacy):
    href = _link(root, "contracts.ndjson", relation="describedby")
    if not href and allow_legacy:
        return _legacy_contracts_url(sources, entry, root)
    return urljoin(entry, _require(href, "HTML contracts"))


def _contract_records(sources, cards_url):
    if cards_url not in sources.declarations:
        sources.declarations[cards_url] = dict(_cards(sources.read(cards_url)))
    return sources.declarations[cards_url]


def _contracts(sources, entry, root, question, chars, adaptive, *, scoped=False, has_markdown=True,
               allow_legacy=False, linked=False):
    cards_url = _contracts_url(sources, entry, root, allow_legacy)
    cards = _contract_records(sources, cards_url)
    member = _choose(question, list(cards), adaptive)
    passage = member.search_text if member else ""
    if adaptive and member and has_markdown:
        packet = select_evidence(question, member, passage, chars, linked=linked)
        if (scoped and cards[member].get("detailsOmitted")) or needs_detail(question, member, packet):
            target = urljoin(cards_url, quote(member.path + ".md", safe="/"))
            detail = markdown_detail(sources.read(target, "content"), member, cards[member],
                                     normalize_contracts=scoped)
            if detail:
                passage = detail
    return member, passage


def _candidate(member, excerpt):
    return dict(page=member.owner, path=member.path, anchor=member.anchor, excerpt=excerpt)


def _ranked_candidates(question, members):
    calls = list(_CALL.finditer(question))
    labels = {m.label.partition("(")[0] for m in members if "(" in m.anchor}
    if any(call["owner"] or call["name"] in labels for call in calls):
        matches = _overloads(question, members)
    else:
        matches = _scope(question, members)
        intent = _KIND_QUESTION.search(question)
        if intent:
            matches = [m for m in matches if _matches_kind(m, intent[1].lower())]
        names = [m for m in matches if m.anchor and re.search(
            r"(?<![\w$])" + re.escape(m.label.partition("(")[0]) + r"(?![\w$])", question)]
        if names:
            matches = names
    pool = {(m.path, m.anchor): m for m in prefer_return_type(question, matches)}
    texts = {key: f"{m.owner} {m.label} {m.summary} {m.search_text}" for key, m in pool.items()}
    return [pool[key] for key in rank_contract(question, texts)] if texts else []


def _candidate_evidence(sources, entry, root, question, variant, chars, count, member, passage,
                        excerpt, answer, allow_legacy):
    singleton = [_candidate(member, excerpt)] if member and excerpt else []
    requested = requested_return_type(question)
    if not requested and member and (
            _overloads(question, [member]) or (answer and _named_type(question, [member.path]))):
        return singleton
    if variant.startswith("javadoc"):
        index, site = _javadoc_index(sources, entry, root)
        records = _javadoc_members(sources, index, site, bool(requested))
    else:
        cards_url = _contracts_url(sources, entry, root, allow_legacy)
        records = _contract_records(sources, cards_url)
    ranked = _ranked_candidates(question, records)
    entries = []
    reverse = bool(re.match(r"^(?:which|what)\b", question, re.I))
    if reverse and member and contradicts_description(question, passage):
        # A reverse lookup found the owner but the description denies the
        # requested behavior. Check other declarations on that owner first.
        singleton = []
        owner_records = [m for m in records if m.path == member.path and m.anchor
                         and ("(" in m.anchor) == ("(" in member.anchor)
                         and description_overlap(question, m.search_text or m.summary) >= 2
                         and not contradicts_description(question, m.search_text or m.summary)]
        if owner_records:
            ranked = _ranked_candidates(question, owner_records)
        else:
            return []
    primary = next((m for m in ranked if member and (m.path, m.anchor) == (member.path, member.anchor)), None)
    if singleton and requested and not primary:
        singleton = []
    if singleton:
        entries.append((primary or member, passage))
    keys = {(m.path, m.anchor) for m, _ in entries}
    for candidate in ranked:
        if len(entries) >= count:
            break
        key = (candidate.path, candidate.anchor)
        if reverse and contradicts_description(question, candidate.search_text or candidate.summary):
            continue
        if key not in keys:
            entries.append((candidate, candidate.search_text or candidate.summary))
            keys.add(key)
    if len(entries) < 2 and singleton:
        return singleton
    evidence = []
    remaining = chars
    for index, (candidate, raw) in enumerate(entries):
        body = raw.removeprefix(candidate.label + ": ").lstrip()
        owner = candidate.owner
        if any(other.owner == owner and other.path != candidate.path for other, _ in entries):
            owner = candidate.path.replace("/", ".")
        prefix = owner + " · "
        if requested and candidate.return_type:
            prefix += candidate.return_type + " · "
        # Give the highest-ranked declarations more context and reuse any unspent space.
        count_left = len(entries) - index
        available = remaining * (count_left + 1) // sum(range(2, count_left + 2))
        size = available - len(prefix) - 5
        selected = select_evidence(question, candidate, body, size, linked=True)
        is_primary = member and (candidate.path, candidate.anchor) == (member.path, member.anchor)
        if singleton and is_primary and any(
                tag + ":" in excerpt and tag + ":" not in selected for tag in _preferences(question)):
            return singleton
        if selected and selected != candidate.label + ":":
            evidence.append(_candidate(candidate, prefix + selected))
            remaining -= len(prefix) + len(selected) + 5
        elif singleton and is_primary:
            return singleton
    return evidence if len(evidence) > 1 or not singleton else singleton


def route(entry_url, question, variant, *, chars=600, encode=None, allow_https=False, file_root=None,
          allow_legacy=False, alternatives=1):
    """Follow a documented route from a local HTML page under a shared excerpt limit."""
    if variant not in ROUTES or chars <= 0:
        raise ValueError("Choose a known route and a positive excerpt length")
    if not 1 <= alternatives <= 3 or (alternatives > 1 and variant not in {
            "scoped", "javadoc-adaptive", "javadoc-scoped"}):
        raise ValueError("Alternative evidence requires a scoped or full Javadoc reader and 1–3 candidates")
    sources = _Sources(entry_url, encode, allow_https=allow_https, file_root=file_root)
    entry = sources.entry
    root = Tree(sources.read(entry)).root
    # Older HTML also marks the LLM indexes as Markdown alternates.
    markdown_alternates = [tag.get("href") for tag in root.iter("link")
                           if tag.get("type") == "text/markdown"
                           and "alternate" in tag.get("rel", "").split()
                           and tag.get("href")
                           and urlsplit(tag.get("href")).path.endswith(".md")]
    twin = markdown_alternates[0] if len(markdown_alternates) == 1 else None
    adaptive = variant in {"javadoc-adaptive", "javadoc-lazy", "javadoc-scoped", "adaptive", "scoped"}
    if variant in {"javadoc", "javadoc-adaptive", "javadoc-lazy"}:
        member, passage = _javadoc(sources, entry, root, question, adaptive, lazy=variant == "javadoc-lazy",
                                    return_types=alternatives > 1 and bool(requested_return_type(question)))
    elif variant == "javadoc-scoped":
        member, passage = _javadoc_scoped(sources, entry, root, question)
    elif variant in {"direct", "adaptive", "scoped"}:
        scoped = _scoped(sources, entry, root, question, chars) if variant == "scoped" and twin else None
        member, passage = scoped or _contracts(sources, entry, root, question, chars, adaptive,
                                               scoped=variant == "scoped", has_markdown=bool(twin),
                                               allow_legacy=allow_legacy, linked=alternatives > 1)
    else:
        llms_url = urljoin(entry, _require(_link(root, "llms.txt"), "HTML llms.txt"))
        llms = sources.read(llms_url)
        if variant == "inline":
            member, passage = _inline(sources, llms_url, llms, question)
        else:
            cards = [member for member, _ in _cards(sources.read(
                _legacy_contracts_url(sources, entry, root)))]
            member = choose_contract(question, cards, rank_contract)
            passage = member.search_text
    if variant in {"scoped", "javadoc-scoped"} and member:
        # The evidence selector supplies the title once for both native type readers.
        passage = passage.removeprefix(member.label + ": ").lstrip()
    excerpt = (select_evidence(question, member, passage, chars, linked=alternatives > 1)
               if adaptive and member else passage[:chars])
    extract = extract_adaptive_answer if adaptive else extract_contract_answer
    answer = extract(question, excerpt, member.owner) if member and (adaptive or len(passage) <= chars) else None
    if answer and answer.rule == "member-description" and contradicts_description(question, passage):
        answer = None
    additional = {}
    if alternatives > 1:
        from eval_packet import pack_candidates
        candidates = _candidate_evidence(sources, entry, root, question, variant, chars, alternatives,
                                         member, passage, excerpt, answer, allow_legacy)
        if len(candidates) > 1:
            candidates = pack_candidates(candidates, max_chars=chars)
        primary = candidates[0] if candidates else None
        changed_primary = primary and (not member or (primary["path"], primary["anchor"]) !=
                                       (member.path, member.anchor))
        if len(candidates) > 1 or changed_primary:
            member = Member(primary["path"], primary["anchor"], primary["page"], "")
            excerpt = ("\n".join(f"[{item['id']}] {item['excerpt']}" for item in candidates)
                       if len(candidates) > 1 else primary["excerpt"])
            answer = None
        elif not candidates:
            member, excerpt, answer = None, "", None
        additional["candidates"] = candidates
    requests = sources.requests
    return dict(page=member.owner if member else None, path=member.path if member else None,
                anchor=member.anchor if member else None, excerpt=excerpt,
                extraction=asdict(answer) if answer else None,
                fetches=len(requests), bytes=sum(r["bytes"] for r in requests), requests=requests,
                discovery_tokens=sum(r["tokens"] for r in requests if r["stage"] == "discovery") if encode else None,
                source_tokens=sum(r["tokens"] for r in requests) if encode else None,
                excerpt_tokens=len(encode(excerpt)) if encode else None,
                markdown_url=urljoin(entry, twin) if twin else None, **additional)
