"""Look up an API declaration and return a cited answer or a reader excerpt."""
import argparse
from dataclasses import asdict, replace
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

from eval_discovery import choose_contract, choose_member, enrich_jdk_members, load_members, member_passage
from eval_extract import extract_answer, extract_contract_answer
from eval_rank import rank, rank_contract


def contract_passage(label, card):
    """Render only documented fields. Keep the member name and overload signature."""
    fields = [label + ":", card.get("summary", "")]
    if card.get("details"):
        fields.append(card["details"])
    if "deprecated" in card:
        fields.append("Deprecated. " + card["deprecated"].get("doc", ""))
    for param in card.get("params", []):
        if param.get("doc"):
            fields.append("Parameters: " + param["name"] + " - " + param["doc"])
    returns = card.get("returns", {}).get("doc")
    if returns:
        fields.append("Returns: " + returns)
    for thrown in card.get("throws", []):
        fields.append("Throws: " + thrown["type"] + " - " + thrown.get("doc", ""))
    return " ".join(" ".join(fields).split())


class Reference:
    def __init__(self, directory, fmt, full_index=True):
        self.full_index = full_index
        self.directory = directory
        self.fmt = fmt
        self.members = load_members(directory, fmt)
        self.cards = {}
        contracts = directory / "contracts.ndjson"
        if fmt == "markdown" and contracts.exists():
            with contracts.open() as stream:
                for line in stream:
                    card = json.loads(line)
                    url = urlsplit(card["href"])
                    self.cards[(url.path.removesuffix(".html"), unquote(url.fragment))] = card

        if full_index:
            if fmt == "html":
                self.members = enrich_jdk_members(directory, self.members)
            else:
                self.members = [
                    replace(m, search_text=contract_passage(m.label, self.cards[(m.path, m.anchor)]))
                    if (m.path, m.anchor) in self.cards else m for m in self.members]

    def lookup(self, question, chars):
        choose = choose_contract if self.full_index else choose_member
        member = choose(question, self.members, rank_contract if self.full_index else rank)
        card = self.cards.get((member.path, member.anchor))
        if self.full_index and member.search_text:
            excerpt = member.search_text
        elif card:
            excerpt = contract_passage(member.label, card)
        else:
            ext = ".html" if self.fmt == "html" else ".md"
            source = (self.directory / (member.path + ext)).read_text()
            excerpt = member_passage(source, self.fmt, member)
        if self.full_index:
            hit = extract_contract_answer(question, excerpt, member.owner) if len(excerpt) <= chars else None
        else:
            hit = extract_answer(question, excerpt[:chars], member.owner)
        excerpt = excerpt[:chars]
        return dict(page=member.owner, path=member.path, anchor=member.anchor, excerpt=excerpt,
                    extraction=asdict(hit) if hit else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", help="Documentation directory, HTML file, or entry URL")
    parser.add_argument("question")
    parser.add_argument("--format", choices=["html", "markdown"], default="markdown",
                        help="Use html for standard Javadoc")
    parser.add_argument("--html-entry", action="store_true",
                        help="Start at index.html when given a directory")
    parser.add_argument("--site-root", type=Path,
                        help="Local documentation root when starting at a nested HTML file")
    parser.add_argument("--chars", type=int, default=600, help="Maximum excerpt characters")
    args = parser.parse_args()
    if args.chars <= 0:
        parser.error("--chars must be positive")
    location = urlsplit(args.site)
    site = Path(args.site)
    url = location.scheme in {"file", "http", "https"} or "://" in args.site
    if args.html_entry or url or site.suffix.lower() == ".html" or args.site_root:
        from eval_cited import extract_cited_answer
        from eval_routes import route
        if url:
            entry = args.site
            directory_url = (location.scheme in {"http", "https"} and
                             (not location.path or location.path.endswith("/")))
            file_directory = (location.scheme == "file" and not location.netloc and
                              Path(unquote(location.path)).is_dir())
            if directory_url or file_directory:
                entry = location._replace(path=location.path.rstrip("/") + "/index.html").geturl()
        else:
            entry = (site / "index.html" if site.is_dir() else site).resolve().as_uri()
        chosen = "javadoc-scoped" if args.format == "html" else "scoped"
        try:
            result = route(entry, args.question, chosen, chars=args.chars, allow_https=True,
                           file_root=args.site_root, allow_legacy=True)
            if result["page"] and not result["extraction"]:
                cited = extract_cited_answer(args.question, result["excerpt"], result["page"])
                if cited:
                    result["extraction"] = asdict(cited)
        except (OSError, ValueError) as error:
            parser.exit(2, f"query_docs: {error}\n")
    else:
        result = Reference(site, args.format).lookup(args.question, args.chars)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
