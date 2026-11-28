"""Matched account evaluation of local-first lookup on published member indexes."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import re
import statistics
from time import perf_counter

from eval_accounts import read_answer
from query_docs import Reference


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--questions", type=Path, required=True)
    p.add_argument("--javadoc", type=Path, required=True)
    p.add_argument("--markdown", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--model")
    p.add_argument("--full-index", action="store_true", help="Search full member facts and require complete fields")
    p.add_argument("--account", choices=["codex", "claude"], default="codex")
    p.add_argument("--model-only", action="store_true", help="Call the reader on every excerpt")
    p.add_argument("--offline", action="store_true", help="Record abstentions without model calls")
    args = p.parse_args()
    fixtures = json.loads(args.questions.read_text())
    args.model = args.model or ("sonnet" if args.account == "claude" else "gpt-6-luna")
    sites = {}
    setup_ms = {}
    for fmt, directory in [("html", args.javadoc), ("markdown", args.markdown)]:
        start = perf_counter()
        sites[fmt] = Reference(directory, fmt, args.full_index)
        setup_ms[fmt] = round((perf_counter() - start) * 1000, 3)
    rows = []
    report = dict(model=args.model, full_index=args.full_index, index_build_ms=setup_ms, policy="model-only" if args.model_only else "local-first",
                  offline=args.offline, fixture_sha256=sha256(args.questions.read_bytes()).hexdigest(),
                  character_budget=600, results=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for i, case in enumerate(fixtures):
        question = case["question"]
        for fmt in list(sites)[::(-1 if i % 2 else 1)]:
            start = perf_counter()
            row = sites[fmt].lookup(question, report["character_budget"])
            hit = row["extraction"]
            row.update(format=fmt, question=question, answer="", input_tokens=0, output_tokens=0,
                       cached_input_tokens=0, model_called=False)
            if hit and not args.model_only:
                row["answer"] = hit["answer"]
            elif not args.offline:
                prompt = ("Answer with the shortest correct identifier or phrase. No explanation. "
                          f"If unknown: unknown.\n{question}\n{row['excerpt']}")
                row.update(read_answer(args.account, args.model, prompt), model_called=True)
            row["wall_ms"] = round((perf_counter() - start) * 1000, 3)
            row["evidence_hit"] = bool(row["page"] == case["page"] and
                                       re.search(case["evidence"], row["excerpt"], re.I | re.S))
            row["grounded_correct"] = bool(row["evidence_hit"] and
                                           re.search(case["answer"], row["answer"], re.I))
            rows.append(row)
            temp = args.output.with_suffix(".tmp")
            temp.write_text(json.dumps(report, indent=2) + "\n")
            temp.replace(args.output)
    for fmt in sites:
        arm = [r for r in rows if r["format"] == fmt]
        print(fmt, {k: sum(r[k] for r in arm) for k in
                    ("grounded_correct", "evidence_hit", "model_called", "input_tokens", "output_tokens")},
              "median ms", statistics.median(r["wall_ms"] for r in arm))


if __name__ == "__main__":
    main()
