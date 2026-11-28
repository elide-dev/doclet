"""Look up a local API declaration and return a cited answer or a reader excerpt."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

from eval_discovery import choose_member, load_members, member_passage
from eval_extract import extract_answer
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
    for param in card.get("params", []):
        if param.get("doc"):
            fields.append("Parameter " + param["name"] + ": " + param["doc"])
    return " ".join(" ".join(fields).split())


class Reference:
    def __init__(self, directory, fmt):
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

    def lookup(self, question, chars):
        member = choose_member(question, self.members, rank)
        card = self.cards.get((member.path, member.anchor))
        if card:
            excerpt = contract_passage(member.label, card)
        else:
            ext = ".html" if self.fmt == "html" else ".md"
            source = (self.directory / (member.path + ext)).read_text()
            excerpt = member_passage(source, self.fmt, member)
        excerpt = excerpt[:chars]
        hit = extract_answer(question, excerpt, member.owner)
        return dict(page=member.owner, path=member.path, anchor=member.anchor, excerpt=excerpt,
                    extraction=asdict(hit) if hit else None)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    parser.add_argument("question")
    parser.add_argument("--format", choices=["html", "markdown"], default="markdown")
    args = parser.parse_args()
    print(json.dumps(Reference(args.site, args.format).lookup(args.question, 600), indent=2))
