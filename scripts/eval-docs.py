"""Paired retrieval and optional answer evaluation on the public Java sample."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import tempfile
from time import perf_counter
from dataclasses import asdict
from eval_extract import extract_answer
from eval_accounts import read_answer
from eval_passages import passage
from eval_rank import rank


class VisibleText(HTMLParser):
    """Collect visible text from Javadoc's main landmark or an index cell."""

    def __init__(self, main_only=False):
        super().__init__()
        self.enabled = not main_only
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "main":
            self.enabled = True

    def handle_endtag(self, tag):
        if tag == "main":
            self.enabled = False

    def handle_data(self, data):
        if self.enabled:
            self.parts.append(data)


def visible(source, main_only=False):
    parser = VisibleText(main_only)
    parser.feed(source)
    return " ".join(" ".join(parser.parts).split())


def evaluate(args, standard):
    module = Path(".dev/pages/catalog/latest/api/java")
    jdk = {}
    table = (standard / "allclasses-index.html").read_text()
    for link, description in re.findall(
        r'class="col-first [^"]*"><a href="example/runtime/([\w.]+)\.html".*?</div>\s*'
        r'<div class="col-last [^"]*">\s*<div class="block">(.*?)</div>', table, re.S
    ):
        jdk[link] = f"{link} example/runtime/{link}.html " + visible(description)
    llms = {name: line for line in (module / "llms.txt").read_text().splitlines()
            for name in re.findall(r'\]\(example/runtime/([\w.]+)\.md\)', line) if name != "index"}
    assert jdk and jdk.keys() == llms.keys(), "Type indexes must cover identical documents"
    questions = json.loads(Path("samples/evals/questions.json").read_text())
    rows = []
    indexes = {"javadoc": jdk, "llms": llms, "names-only": {name: name for name in jdk}}
    variants = [(i, f) for i in indexes for f in ("html", "markdown")]
    if args.paired or args.account and not args.all_variants:
        variants = [("javadoc", "html"), ("llms", "markdown")]
    encoding = None
    if args.count_tokens:
        import tiktoken
        encoding = tiktoken.get_encoding("o200k_base")
    report = dict(extract_first=args.extract_first, layout=args.layout, passages=args.passages, terse=args.terse,
                  encoding=encoding.name if encoding else None, character_budget=args.chars, reader=args.model or args.reader_label,
                  reasoning_effort=args.effort if args.account == "codex" else None,
                  cli_version=subprocess.check_output(
                      [args.account, "--version"], text=True).strip() if args.account else None,
                  results=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for n, case in enumerate(questions):
        for discovery, fmt in (variants if n % 2 == 0 else reversed(variants)):
            start = perf_counter()
            index = indexes[discovery]
            chosen = rank(case["question"], index)[0]
            relative = Path("example/runtime") / chosen
            if fmt == "html":
                source = (standard / (str(relative) + ".html")).read_text()
                body = visible(source, True)
            else:
                source = body = (module / (str(relative) + ".md")).read_text()
            if args.layout != "prefix":
                body = passage(source, fmt, args.layout, case["question"], chosen, rank, args.passages) or body
            context = body[:args.chars]
            hit = chosen == case["page"]
            evidence = bool(hit and re.search(case["evidence"], context, re.I | re.S))
            row = dict(index=discovery, format=fmt, question=case["question"], page=chosen,
                       page_hit=hit, evidence_hit=evidence, context_chars=len(context))
            if encoding:
                row["document_tokens"] = len(encoding.encode_ordinary(context))
                row["excerpt"] = context
            if args.reader or args.account:
                prompt = f"Answer briefly using only this document. If unknown, say unknown.\nQuestion: {case['question']}\nDocument:\n{context}"
                if args.terse:
                    prompt = f"Answer with the shortest correct identifier or phrase. No explanation. If unknown: unknown.\n{case['question']}\n{context}"
                hit = extract_answer(case["question"], context, chosen) if args.extract_first else None
                if hit:
                    answer = hit.answer
                    row.update(extraction=asdict(hit), model_called=False, input_tokens=0,
                               output_tokens=0, cached_input_tokens=0, cache_write_tokens=0)
                elif args.account:
                    row["model_called"] = True
                    row.update(read_answer(args.account, args.model, prompt, args.effort))
                    answer = row["answer"]
                else:
                    answer = subprocess.run(shlex.split(args.reader), input=prompt, text=True,
                                        capture_output=True, timeout=90, check=True).stdout.strip()
                row.update(answer=answer, grounded_correct=bool(evidence and
                           re.search(case["answer"], answer, re.I)))
            row["wall_ms"] = round((perf_counter() - start) * 1000, 3)
            rows.append(row)
            # Preserve completed turns if a local account hits its quota mid-run.
            pending = args.output.with_suffix(".tmp")
            pending.write_text(json.dumps(report, indent=2) + "\n")
            pending.replace(args.output)
    for index in indexes:
        for fmt in ("html", "markdown"):
            group = [r for r in rows if (r["index"], r["format"]) == (index, fmt)]
            if not group:
                continue
            metrics = ["page_hit", "evidence_hit"] + (["grounded_correct"] if args.reader or args.account else [])
            print(index, fmt, *(f"{k}={sum(r[k] for r in group)}/{len(group)}" for k in metrics),
                  f"median wall={statistics.median(r['wall_ms'] for r in group):.1f}ms",
                  "mean context chars=", round(statistics.mean(r["context_chars"] for r in group)))
            if args.account:
                print("  token totals:", {key: sum(r[key] for r in group) for key in
                      ("input_tokens", "cached_input_tokens", "cache_write_tokens", "output_tokens")})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--layout", choices=["prefix", "bullets", "table", "symbols"], default="prefix")
    parser.add_argument("--passages", type=int, choices=range(1, 9), default=1)
    parser.add_argument("--extract-first", action="store_true", help="Use a cited local answer when available")
    parser.add_argument("--terse", action="store_true")
    parser.add_argument("--count-tokens", action="store_true", help="Count excerpt tokens using tiktoken o200k_base")
    parser.add_argument("--account", choices=["codex", "claude"])
    parser.add_argument("--effort", choices=["low", "medium", "high", "xhigh", "max", "ultra"], default="low")
    parser.add_argument("--model", help="Provider model ID or Claude alias")
    variants = parser.add_mutually_exclusive_group()
    variants.add_argument("--paired", action="store_true", help="Compare the two delivery stacks")
    variants.add_argument("--all-variants", action="store_true", help="Include all discovery/format ablations")
    parser.add_argument("--chars", type=int, default=4000)
    parser.add_argument("--reader", help="Optional model wrapper command: prompt on stdin, answer on stdout")
    parser.add_argument("--reader-label", help="Model/version and settings to record")
    parser.add_argument("--output", type=Path, default=Path(".dev/quality/doc-evals.json"))
    args = parser.parse_args()
    if args.extract_first and not (args.account or args.reader):
        parser.error("--extract-first requires a fallback reader")
    if args.account:
        if args.reader:
            parser.error("Choose either an account or an external reader")
        args.model = args.model or ("gpt-6-luna" if args.account == "codex" else "sonnet")
    elif args.model:
        parser.error("--model requires --account")
    if args.chars <= 0 or bool(args.reader) != bool(args.reader_label):
        parser.error("Provide a positive budget and pair --reader with --reader-label")
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["javadoc", "-quiet", "-notimestamp", "-notree", "-d", tmp,
                        *sorted(Path("samples/java").rglob("*.java"))], check=True)
        evaluate(args, Path(tmp))
