"""Locate missed evidence after a finished model-directed navigation experiment.

Shown uses the frozen grader's first on-screen evidence. Fetched scans the same
navigator's citable rows across the archived requests, grouped by canonical
declaration. Complete supporting text may span rows. The analysis does not
count an unanchored clue, a related overload, or a paraphrase that misses the
fixture regex. An out-of-browser stop is a miss regardless of what it fetched.
"""
import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path, PurePosixPath
import runpy
import sys
from urllib.parse import unquote, urldefrag

SCRIPTS = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(SCRIPTS))
from eval_navigation import Navigator
from eval_reviews import load_reviews, review_corrections

driver = runpy.run_path(str(SCRIPTS / "eval-browse.py"))
reporter = runpy.run_path(str(SCRIPTS / "report-browse.py"))


def canonical_path(root, case):
    # Fixtures may give only the leaf type name. Refuse to conflate namesakes.
    target = case["page"]
    if "/" in target:
        paths = [root / (target + ".html")]
    else:
        paths = [p for p in root.rglob("*.html") if p.stem == target]
    if len(paths) != 1 or not paths[0].is_file():
        raise ValueError(f"Expected one canonical HTML page for {target} in {root}")
    return paths[0].relative_to(root).with_suffix("").as_posix()


def fetched_support(root, trial, case, width):
    navigator = Navigator(root, window_chars=width)
    canonical = canonical_path(root, case)
    all_rows, supporting_files = [], []
    for request in trial["requests"]:
        source = request["path"]
        rows, _ = navigator._load(source)
        citations = []
        for row in rows:
            destination, anchor = urldefrag(row.destination)
            path = PurePosixPath(unquote(destination)).with_suffix("")
            if path.as_posix() == canonical:
                citations.append({"path": canonical, "page": case["page"],
                                  "anchor": unquote(anchor), "excerpt": row.text})
        if driver["supports"](case, citations):
            supporting_files.append(source)
        all_rows.extend(citations)
    if navigator.requests != trial["requests"]:
        raise ValueError(f"Fetched path or size mismatch for {trial['site']} case {trial['case']}")
    if not supporting_files and driver["supports"](case, all_rows):
        supporting_files.append("(combined rows across fetched files)")
    return supporting_files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--questions", type=Path, required=True)
    parser.add_argument("--site", action="append", required=True, metavar="ARM=DIRECTORY")
    parser.add_argument("--reviews", type=Path, help="Apply checksum-bound citation reviews")
    parser.add_argument("--partial", action="store_true", help="Limit an unfinished archive to completed paired cases")
    args = parser.parse_args()

    raw = args.archive.read_bytes()
    archive = json.loads(raw)
    questions = args.questions.read_bytes()
    if sha256(questions).hexdigest() != archive["fixture_sha256"]:
        parser.error("Question fixture differs from the archived fixture")
    for name in ["eval_navigation.py", "eval-browse.py"]:
        if sha256((SCRIPTS / name).read_bytes()).hexdigest() != archive["source_sha256"][name]:
            parser.error(f"Use the archived version of {name}")
    sites = {arm: Path(root) for arm, root in (arg.split("=", 1) for arg in args.site)}
    if set(sites) != set(archive["sites"]) or any(
            driver["document_digest"](root) != archive["sites"][arm] for arm, root in sites.items()):
        parser.error("Supply the original site snapshots for all archived arms")

    cases = json.loads(questions)
    _, groups = reporter["paired_trials"](archive, args.partial)
    by_site = {arm: {r["question"]: r for r in archive["results"] if r["site"] == arm} for arm in sites}
    reviews = review_corrections(args.archive, sha256(raw).hexdigest(), load_reviews(args.reviews), by_site)
    print("| Site | Frozen correct | Reviewed correct | Shown, missed | Fetched, unseen | No support fetched |")
    print("| --- | ---: | ---: | ---: | ---: | ---: |")
    details = []
    for arm, trials in groups.items():
        counts = Counter()
        for trial in trials:
            number = trial["case"]
            case = cases[number - 1]
            if case["question"] != trial["question"]:
                parser.error(f"Fixture question differs for case {number}")
            counts["frozen"] += trial["correct"]
            if reviews.get((arm, trial["question"]), trial)["correct"]:
                counts["correct"] += 1
                continue
            files = fetched_support(sites[arm], trial, case, archive["window_chars"])
            if trial["first_evidence_after_actions"] is not None:
                category = "shown"
            elif files:
                category = "unseen"
            else:
                category = "missing"
            counts[category] += 1
            details.append((arm, number, category, files))
        print(f"| {arm} | {counts['frozen']} | {counts['correct']} | {counts['shown']} "
              f"| {counts['unseen']} | {counts['missing']} |")
    if details:
        print("\n| Site | Missed case | Evidence | Supporting fetched files |")
        print("| --- | ---: | --- | --- |")
        for arm, number, category, files in details:
            print(f"| {arm} | {number} | {category} | {', '.join(files) or '—'} |")


if __name__ == "__main__":
    main()
