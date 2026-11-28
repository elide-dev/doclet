"""Run one local account turn per arm over an offline paired excerpt report."""
import argparse
import json
import re
from pathlib import Path
from time import perf_counter

from eval_accounts import read_answer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--account", choices=["codex", "claude"], default="codex")
    parser.add_argument("--model")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    model = args.model or ("gpt-6-luna" if args.account == "codex" else "sonnet")
    source = json.loads(args.report.read_text())
    fixtures = json.loads(Path("samples/evals/questions.json").read_text())
    questions = [case["question"] for case in fixtures]
    result = dict(source=args.report.name, model=model, arms=[])
    for index, fmt in [("javadoc", "html"), ("llms", "markdown")]:
        rows = [r for r in source["results"] if (r["index"], r["format"]) == (index, fmt)]
        if [r["question"] for r in rows] != questions:
            raise ValueError("Expected exactly one excerpt per fixture in question order")
        tasks = [dict(question=r["question"], document=r["excerpt"]) for r in rows]
        prompt = ("Return a JSON array of short answer strings in order, one per item. "
                  "Use only that item's document. Write unknown if insufficient.\n" + json.dumps(tasks))
        start = perf_counter()
        turn = read_answer(args.account, model, prompt)
        elapsed = round(1000 * (perf_counter() - start))
        raw = turn.pop("answer").strip()
        answers = json.loads(re.sub(r"^\x60\x60\x60(?:json)?\s*|\s*\x60\x60\x60$", "", raw))
        if not isinstance(answers, list) or len(answers) != len(rows) or not all(isinstance(a, str) for a in answers):
            raise ValueError("Expected one answer string per question")
        scores = [bool(r["evidence_hit"] and re.search(q["answer"], a, re.I))
                  for q, r, a in zip(fixtures, rows, answers)]
        result["arms"].append(dict(index=index, format=fmt, answers=answers, correct=scores,
                                   wall_ms=elapsed, **turn))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(index, sum(scores), "/", len(scores), turn, elapsed, "ms")


if __name__ == "__main__":
    main()
