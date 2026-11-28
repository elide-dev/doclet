"""Measure evidence available from alternate rankings of empty, recorded literal searches."""
import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import re
import runpy
import sys
from urllib.parse import unquote, urldefrag

SCRIPTS = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(SCRIPTS))
from eval_navigation import Navigator
from eval_rank import rank, rank_contract, words

# Use the experiment's existing site checksum and same-declaration evidence rules.
driver = runpy.run_path(str(SCRIPTS / "eval-browse.py"))


def empty_queries(trial):
    for turn, following in zip(trial["trace"], trial["trace"][1:]):
        query = turn.get("command", {}).get("query")
        observation = following["observation"]
        if query and not observation["evidence_ids"] and "literal matches in this document." in observation["text"]:
            yield observation["url"], query.strip()


def candidates(rows, query, ranker):
    entries = {i: re.sub(r"\s*\[L\d+\]", "", row.text) for i, row in enumerate(rows)}
    if not entries:
        return []
    # The contract ranker also scores terms split out of camel-case identifiers.
    def score_terms(text):
        if ranker is rank_contract:
            text = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)
        return set(words(text))

    terms = score_terms(query)
    result = []
    for i in ranker(query, entries)[:3]:
        if not terms.intersection(score_terms(entries[i])):
            continue
        row = rows[i]
        path, fragment = urldefrag(row.destination)
        path = Path(unquote(path))
        result.append({"page": path.stem, "path": str(path.with_suffix("")), "anchor": unquote(fragment),
                       "excerpt": row.text})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--questions", type=Path, required=True)
    parser.add_argument("--site", action="append", required=True, metavar="ARM=DIRECTORY")
    args = parser.parse_args()
    archive = json.loads(args.archive.read_bytes())
    fixture = args.questions.read_bytes()
    sites = dict(arg.split("=", 1) for arg in args.site)
    if set(sites) != set(archive["sites"]) or any(
            driver["document_digest"](Path(path)) != archive["sites"][arm] for arm, path in sites.items()):
        parser.error("Supply the original site snapshots for all archived arms")
    if sha256(fixture).hexdigest() != archive["fixture_sha256"]:
        parser.error("Question fixture differs from the archive")
    if sha256((SCRIPTS / "eval_navigation.py").read_bytes()).hexdigest() != archive["source_sha256"]["eval_navigation.py"]:
        parser.error("Use the navigator revision recorded in the archive")

    questions = json.loads(fixture)
    totals = {ranker: Counter() for ranker in (rank, rank_contract)}
    for trial in archive["results"]:
        nav = Navigator(sites[trial["site"]], archive["window_chars"])
        for page, query in empty_queries(trial):
            rows, _ = nav._load(page)
            # An exhausted repeated find can produce an empty view despite earlier exact matches.
            if any(query.casefold() in row.text.casefold() for row in rows):
                continue
            for ranker, count in totals.items():
                top = candidates(rows, query, ranker)
                case = questions[trial["case"] - 1]
                count["misses"] += 1
                count["target"] += any(driver["matches_target"](case, c) for c in top)
                if driver["supports"](case, top):
                    count["support"] += 1
                    print(f"{ranker.__name__}: case {trial['case']}, {trial['site']}, {page}, {query!r}",
                          [c["anchor"] for c in top])
    for ranker, count in totals.items():
        print(f"{ranker.__name__}: {count['misses']} zero-literal queries, {count['target']} with the target, "
              f"{count['support']} with supporting text in the first three rows")


if __name__ == "__main__":
    main()
