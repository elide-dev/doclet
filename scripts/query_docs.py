"""Look up a local API declaration and return a cited answer or a reader excerpt."""
import argparse
from dataclasses import asdict, replace
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

from eval_discovery import choose_contract, choose_member, enrich_jdk_members, load_members, member_passage
from eval_extract import extract_answer, extract_contract_answer
from eval_rank import rank


def contract_passage(label, card):
    """Render only documented fields. Keep the member name and overload signature."""
    fields = [label + ":", card.get("summary", "")]
    returns = card.get("returns", {}).get("doc")
    if returns:
        fields.append("Returns: " + returns)
    for thrown in card.get("throws", []):
        fields.append("Throws: " + thrown["type"] + " - " + thrown.get("doc", ""))
    if "deprecated" in card:
        fields.append("Deprecated. " + card["deprecated"].get("doc", ""))
    if card.get("details"):
        fields.insert(2, card["details"])
    for param in card.get("params", []):
        if param.get("doc"):
            fields.insert(2, "Parameters: " + param["name"] + " - " + param["doc"])
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
        member = choose(question, self.members, rank)
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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    parser.add_argument("question")
    parser.add_argument("--format", choices=["html", "markdown"], default="markdown")
    args = parser.parse_args()
    print(json.dumps(Reference(args.site, args.format).lookup(args.question, 600), indent=2))
