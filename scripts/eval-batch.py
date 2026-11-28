"""Run one local account turn per arm over an offline paired excerpt report."""
import argparse
from hashlib import sha256
import json
import re
from pathlib import Path
from time import perf_counter

from eval_accounts import read_answer


def html_entry_batches(args, source, model):
    """Replay the exact singleton evidence from an HTML-entry run, batching only fallbacks."""
    if not args.questions or sha256(args.questions.read_bytes()).hexdigest() != source["fixtures"]:
        raise ValueError("Supply --questions from the source report, with the same fixture checksum")
    if args.account != "codex" or model != "gpt-6-luna":
        raise ValueError("HTML-entry batch comparisons use the same Luna low reader as the independent turns")
    fixtures = json.loads(args.questions.read_text())
    arms = source["routes"]
    result = dict(source=args.report.name, source_sha256=sha256(args.report.read_bytes()).hexdigest(),
                  fixtures=source["fixtures"], model=model, effort="low", order_offset=args.offset, arms=[])
    offset = args.offset % len(arms)
    for arm in arms[offset:] + arms[:offset]:
        rows = [r for r in source["results"] if r["route"] == arm]
        if [r["question"] for r in rows] != [q["question"] for q in fixtures]:
            raise ValueError("Each route must have one row per question, in fixture order")
        if any(len(r.get("candidates", [])) > 1 for r in rows):
            raise ValueError("Batch replay requires single-declaration evidence")
        fallback = [i for i, row in enumerate(rows) if row["excerpt"] and not row["extraction"]]
        answers = [r["extraction"]["answer"] if r["extraction"] else "unknown" for r in rows]
        usage = dict(input_tokens=0, cached_input_tokens=0, output_tokens=0)
        elapsed = 0
        if fallback:
            tasks = {str(i): dict(question=rows[i]["question"], document=rows[i]["excerpt"]) for i in fallback}
            schema = dict(type="object", properties={k: {"type": "string"} for k in tasks},
                          required=list(tasks), additionalProperties=False)
            prompt = ("Answer each numbered question with the shortest correct phrase in its JSON field. "
                      "Use only that item's document. Write unknown if insufficient.\n" + json.dumps(tasks))
            start = perf_counter()
            usage = read_answer("codex", model, prompt, "low", output_schema=schema)
            elapsed = round(1000 * (perf_counter() - start), 2)
            raw = usage.pop("answer").strip()
            replies = json.loads(raw)
            if not isinstance(replies, dict) or replies.keys() != tasks.keys() or not all(isinstance(a, str) for a in replies.values()):
                raise ValueError("Expected one answer string per fallback")
            for i in fallback:
                answers[i] = replies[str(i)]
        scores = [bool(r["evidence_hit"] and all(re.search(p, answer, re.I) for p in
                       [case["answer"], *case.get("answer_all", [])]))
                  for r, case, answer in zip(rows, fixtures, answers)]
        lookup_ms = round(sum(r["lookup_ms"] for r in rows), 2)
        result["arms"].append(dict(route=arm, correct=sum(scores), scores=scores, answers=answers,
                                   calls=int(bool(fallback)), fallback_questions=len(fallback),
                                   model_ms=elapsed, source_lookup_ms=lookup_ms,
                                   lookup_plus_model_ms=round(lookup_ms + elapsed, 2), **usage))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--account", choices=["codex", "claude"], default="codex")
    parser.add_argument("--model")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--questions", type=Path, help="Frozen questions for an HTML-entry report")
    parser.add_argument("--offset", type=int, default=0, help="Rotate the order of HTML-entry batch calls")
    args = parser.parse_args()
    model = args.model or ("gpt-6-luna" if args.account == "codex" else "sonnet")
    source = json.loads(args.report.read_text())
    if "routes" in source:
        html_entry_batches(args, source, model)
        return
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
