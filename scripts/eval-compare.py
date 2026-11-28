"""Compare grounded accuracy and cost across compatible hosted HTML-entry runs."""
import argparse
from fractions import Fraction
from hashlib import sha256
import json
from math import inf, isfinite
from pathlib import Path
import re


AXES = ("correct", "input_tokens", "output_tokens", "fetches", "bytes", "wall_ms", "efficiency")
COSTS = AXES[1:-1]
COUNTS = COSTS[:-1]


def load_reviews(path):
    if path is None:
        return {}
    document = json.loads(path.read_bytes())
    if not isinstance(document, dict):
        raise ValueError("Reviews must map report SHA-256 to correction lists")
    for digest, corrections in document.items():
        if not re.fullmatch(r"[0-9a-f]{64}", digest) or not isinstance(corrections, list) or not corrections:
            raise ValueError("Each review needs a report SHA-256 and a nonempty correction list")
        for review in corrections:
            required = {"route", "question", "correct", "reason"}
            if (not isinstance(review, dict) or not required.issubset(review)
                    or set(review) - required - {"report"} or type(review["correct"]) is not bool
                    or not all(isinstance(review[key], str) and review[key].strip()
                               for key in ("route", "question", "reason"))):
                raise ValueError(f"Invalid correction in review {digest}")
            if "report" in review and (not isinstance(review["report"], str)
                    or not review["report"].strip() or Path(review["report"]).name != review["report"]):
                raise ValueError(f"Invalid report basename in review {digest}")
    return document


def _efficiency(totals):
    correct = totals["correct"]
    tokens = totals["input_tokens"] + totals["output_tokens"]
    return Fraction(correct, tokens) if tokens else (inf if correct else Fraction(0))


def _ratio(candidate, baseline):
    if baseline == candidate == inf or baseline == candidate == 0:
        return None
    if baseline == 0 or candidate == inf:
        return inf
    return candidate / baseline


def _number(number):
    if number == inf:
        return "infinite"
    return float(number) if isinstance(number, Fraction) else number


def load_run(path, pair, reviews):
    data = path.read_bytes()
    digest = sha256(data).hexdigest()
    source = json.loads(data)
    if source.get("offline") is not False or not source.get("model") or not source.get("fixtures"):
        raise ValueError(f"{path.name}: expected a hosted HTML-entry report")
    policy = {key: source.get(key) for key in ("fixtures", "model", "effort", "excerpt_chars")}
    if not all(policy.values()):
        raise ValueError(f"{path.name}: missing fixture, model, effort, or character budget")
    policy.update(alternatives=source.get("alternatives", 1), identifier_ids=source.get("identifier_ids", False),
                  cited=source.get("cited", False))
    routes = source.get("routes") or []
    if pair[0] == pair[1] or len(set(routes)) != len(routes) or not set(pair).issubset(routes):
        raise ValueError(f"{path.name}: choose two distinct declared routes")
    rows = {route: {} for route in routes}
    for row in source.get("results", []):
        route, question = row.get("route"), row.get("question")
        if route not in rows or not isinstance(question, str) or not question.strip() or question in rows[route]:
            raise ValueError(f"{path.name}: unexpected or duplicate route/question row")
        if type(row.get("correct")) is not bool or not isinstance(row.get("answer"), str):
            raise ValueError(f"{path.name}: every row must have a completed hosted grade")
        if any(type(row.get(key)) is not int or row[key] < 0 for key in COUNTS):
            raise ValueError(f"{path.name}: provider tokens, fetches, and bytes must be nonnegative integers")
        elapsed = row.get("wall_ms")
        if type(elapsed) not in {int, float} or elapsed < 0 or not isfinite(elapsed):
            raise ValueError(f"{path.name}: wall time must be finite and nonnegative")
        rows[route][question] = row
    questions = list(rows[pair[0]])
    if not questions or any(list(group) != questions for group in rows.values()):
        raise ValueError(f"{path.name}: question alignment failed across the report's routes")
    if source.get("question_count", len(questions)) != len(questions):
        raise ValueError(f"{path.name}: report has incomplete question counts")
    corrections = {}
    for expected, items in reviews.items():
        if expected != digest and any(review.get("report") == path.name for review in items):
            raise ValueError(f"{path.name}: report checksum differs from its review")
    for review in reviews.get(digest, []):
        if review.get("report", path.name) != path.name:
            raise ValueError(f"{path.name}: review report basename does not match")
        route, question = review["route"], review["question"]
        key = (route, question)
        row = rows.get(route, {}).get(question)
        if not row or key in corrections or row["correct"] == review["correct"]:
            raise ValueError(f"{path.name}: unknown, duplicate, or no-op correction for {route}: {question}")
        corrections[key] = review

    report = dict(report=path.name, sha256=digest, questions=len(questions), policy=policy)
    for label, route in zip(("baseline", "candidate"), pair):
        group = rows[route].values()
        applied = [corrections[(route, q)] for q in questions if (route, q) in corrections]
        totals = {key: sum(r[key] for r in group) for key in COSTS}
        totals.update(route=route, frozen_correct=sum(r["correct"] for r in group), reviews=applied,
                      correct=sum(corrections.get((route, r["question"]), r)["correct"] for r in group))
        report[label] = totals

    baseline, candidate = (report[name] for name in ("baseline", "candidate"))
    base_eff, candidate_eff = _efficiency(baseline), _efficiency(candidate)
    ratio = _ratio(candidate_eff, base_eff)
    comparisons = {}
    for axis in AXES:
        left, right = ((base_eff, candidate_eff) if axis == "efficiency" else (baseline[axis], candidate[axis]))
        if left == right:
            comparisons[axis] = "tie"
        else:
            comparisons[axis] = "win" if (right < left if axis in COSTS else right > left) else "loss"
    baseline["grounded_per_1000_tokens"] = _number(1000 * base_eff)
    candidate["grounded_per_1000_tokens"] = _number(1000 * candidate_eff)
    report.update(efficiency_ratio=_number(ratio), axes=comparisons)
    return report, questions, ratio


def compare(paths, pairs, reviews):
    runs, ratios = [], []
    hashes = set()
    questions = None
    for path, pair in zip(paths, pairs, strict=True):
        report, current, ratio = load_run(path, pair, reviews)
        if report["sha256"] in hashes:
            raise ValueError("A repeated file does not count as an independent run")
        if runs and (report["policy"] != runs[0]["policy"] or current != questions):
            raise ValueError(f"{path.name}: incompatible fixture questions or benchmark policy")
        questions = current
        hashes.add(report["sha256"])
        runs.append(report)
        ratios.append(ratio)
    axes = {}
    for axis in AXES:
        statuses = {run["axes"][axis] for run in runs}
        axes[axis] = statuses.pop() if len(statuses) == 1 else "mixed"
    return dict(runs=runs, axes=axes), ratios


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--baseline", default="javadoc-scoped")
    parser.add_argument("--candidate", default="scoped")
    parser.add_argument("--pair", action="append", metavar="BASELINE:CANDIDATE",
                        help="Override route names once per report")
    parser.add_argument("--reviews", type=Path)
    parser.add_argument("--require-all", action="store_true", help="Require a strict win on every axis in every run")
    parser.add_argument("--min-efficiency", type=float, help="Require this grounded-answers/token ratio in every run")
    args = parser.parse_args()
    if args.min_efficiency is not None and (args.min_efficiency <= 0 or not isfinite(args.min_efficiency)):
        parser.error("--min-efficiency must be positive and finite")
    pairs = [entry.split(":") for entry in args.pair] if args.pair else [
        (args.baseline, args.candidate)] * len(args.reports)
    if len(pairs) != len(args.reports) or any(len(pair) != 2 or not all(pair) for pair in pairs):
        parser.error("Supply one BASELINE:CANDIDATE pair per report")
    try:
        result, ratios = compare(args.reports, pairs, load_reviews(args.reviews))
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2, allow_nan=False))
    wins = all(status == "win" for status in result["axes"].values())
    floor_met = args.min_efficiency is None or all(
        ratio is not None and ratio >= args.min_efficiency for ratio in ratios)
    if (args.require_all and not wins) or not floor_met:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
