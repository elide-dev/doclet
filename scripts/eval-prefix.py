"""Cross type-index cues with Markdown contract bullets at a fixed prefix budget."""
import argparse
import json
from pathlib import Path
import re

import tiktoken

from eval_rank import rank


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    parser.add_argument("questions", type=Path)
    parser.add_argument("--chars", type=int, default=600)
    args = parser.parse_args()
    if args.chars <= 0:
        parser.error("--chars must be positive")
    model = json.loads((args.site / "api.json").read_text())
    types = {"/".join(filter(None, [pkg["name"].replace(".", "/"), t["name"] + ".md"])): t["name"]
             for pkg in model["packages"] for t in pkg.get("types", [])}
    index = (args.site / "llms.txt").read_text()
    pages = {}
    variants = {"with_cues": {}, "summary_only": {}}
    compact = []
    for line in index.splitlines(keepends=True):
        target = re.match(r"^(- \[.*?\]\(([^)]+\.md)\))", line)
        if target and target[2] in types:
            path = target[2]
            body = (args.site / path).read_text()
            description = re.search(r"^description: (.+)$", body.split("\n---\n", 1)[0], re.M)
            pages[path] = body
            variants["with_cues"][path] = line.rstrip()
            summary = json.loads(description[1]) if description else ""
            line = target[1] + (": " + summary if summary else "") + "\n"
            variants["summary_only"][path] = line.rstrip()
        compact.append(line)
    if pages.keys() != types.keys():
        raise ValueError("Expected all generated type pages in llms.txt")
    encode = tiktoken.get_encoding("o200k_base").encode_ordinary
    report = {"chars": args.chars, "index_tokens": {"with_cues": len(encode(index)),
              "summary_only": len(encode("".join(compact)))}, "cases": []}
    for case in json.loads(args.questions.read_text()):
        for variant, descriptions in variants.items():
            path = rank(case["question"], descriptions)[0]
            body = pages[path]
            plain = re.sub(r"^## API at a glance\n\n(?:- [^\n]+\n)+\n?", "", body, flags=re.M)
            hit = types[path] == case["page"]
            for bullets, page in [(True, body), (False, plain)]:
                report["cases"].append(dict(question=case["question"], index=variant, bullets=bullets,
                    page=path, page_hit=hit, evidence_hit=bool(hit and re.search(
                        case["evidence"], page[:args.chars], re.I | re.S))))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
