"""Matched local HTTP discovery experiments. Hosted turns always use Luna low."""
import argparse
from contextlib import contextmanager
from functools import lru_cache, partial
from hashlib import sha256
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
from statistics import median
from tempfile import TemporaryDirectory
from threading import Thread
from time import perf_counter
from urllib.parse import urlsplit

import tiktoken

from eval_accounts import read_answer
from eval_routes import route


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


@contextmanager
def serve(jdk, generated):
    with TemporaryDirectory() as directory:
        root = Path(directory) / "docs"
        root.mkdir()
        (root / "jdk").symlink_to(jdk.resolve())
        (root / "api").symlink_to(generated.resolve())
        compact = root / "compact"
        compact.mkdir()
        for path in generated.iterdir():
            if path.name != "llms.txt":
                (compact / path.name).symlink_to(path.resolve())
        index = (generated / "llms.txt").read_text()
        # Construct an experimental short manifest from the actual published destinations.
        lines = [line for line in index.splitlines() if line.startswith("- [")
                 and re.search(r"\]\((?:contracts\.ndjson|index-all\.md|llms-full\.txt)\)", line)]
        (compact / "llms.txt").write_text(index.splitlines()[0] + "\n\n> API reference.\n\n## Lookups\n\n"
                                          + "\n".join(lines) + "\n")
        server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=directory))
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/docs/"
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


def summarize(rows, resources):
    def total(key):
        return sum(r[key] for r in rows)
    unique = {path for r in rows for path in r["reads"]}
    return dict(questions=len(rows), page_hits=total("page_hit"), evidence_hits=total("evidence_hit"),
                correct=total("correct") if all(r["correct"] is not None for r in rows) else None,
                anchor_hits=sum(r["anchor_hit"] is True for r in rows),
                anchors_graded=sum(r["anchor_hit"] is not None for r in rows),
                model_calls=total("model_called"), input_tokens=total("input_tokens"),
                cached_input_tokens=total("cached_input_tokens"), output_tokens=total("output_tokens"),
                total_model_tokens=total("input_tokens") + total("output_tokens"),
                excerpt_tokens=total("excerpt_tokens"), discovery_tokens=total("discovery_tokens"),
                fetched_tokens=total("fetched_tokens"), fetches=total("fetches"), bytes=total("bytes"),
                unique_fetches=len(unique), unique_bytes=sum(resources[path]["bytes"] for path in unique),
                wall_ms=round(total("wall_ms"), 2), median_wall_ms=median(r["wall_ms"] for r in rows))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--javadoc", required=True, type=Path)
    p.add_argument("--markdown", required=True, type=Path)
    p.add_argument("--questions", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--routes", nargs="+", choices=["javadoc", "inline", "contracts", "compact", "direct", "adaptive", "javadoc-adaptive", "javadoc-lazy"],
                   default=["javadoc", "inline", "contracts", "compact", "direct"])
    p.add_argument("--offset", type=int, default=0, help="Rotate the starting arm across repeated paired runs")
    p.add_argument("--offline", action="store_true")
    args = p.parse_args()
    rows, resources = [], {}
    arms = list(dict.fromkeys(args.routes))
    cases = json.loads(args.questions.read_text())
    report = dict(model="gpt-6-luna", effort="low", offline=args.offline,
                  fixtures=sha256(args.questions.read_bytes()).hexdigest(), excerpt_chars=600,
                  entry="index.html", tokenizer="o200k_base", routes=arms, order_offset=args.offset,
                  cli_version=None if args.offline else subprocess.check_output(["codex","--version"], text=True).strip(),
                  resources=resources, results=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Tokenization is benchmark instrumentation and excluded from wall time.
    encoding = tiktoken.get_encoding("o200k_base")

    @lru_cache(maxsize=512)
    def count_only(body):
        return range(len(encoding.encode_ordinary(body)))

    with serve(args.javadoc, args.markdown) as base:
        for n, case in enumerate(cases):
            position = (n + args.offset) % len(arms)
            for arm in arms[position:] + arms[:position]:
                encoding_s = 0
                def encode(body):
                    nonlocal encoding_s
                    start = perf_counter()
                    result = count_only(body)
                    encoding_s += perf_counter() - start
                    return result

                folder = {"javadoc": "jdk", "javadoc-adaptive": "jdk", "javadoc-lazy": "jdk", "compact": "compact"}.get(arm, "api")
                start = perf_counter()
                outcome = route(base + folder + "/index.html", case["question"],
                                "contracts" if arm == "compact" else arm, encode=encode)
                lookup_ms = (perf_counter() - start - encoding_s) * 1000
                reads = []
                for request in outcome.pop("requests"):
                    path = request["path"]
                    reads.append(path)
                    resources[path] = dict(bytes=request["bytes"], tokens=request["tokens"])
                if outcome["markdown_url"]:
                    outcome["markdown_url"] = urlsplit(outcome["markdown_url"]).path
                row = dict(**outcome, route=arm, question=case["question"], reads=reads,
                           fetched_tokens=sum(resources[path]["tokens"] for path in reads),
                           lookup_ms=round(lookup_ms, 3), model_called=False, input_tokens=0,
                           output_tokens=0, cached_input_tokens=0, answer=None)
                model_ms = 0
                if row["extraction"]:
                    row["answer"] = row["extraction"]["answer"]
                elif not row["excerpt"].strip():
                    row["answer"] = "unknown"
                elif not args.offline:
                    prompt = ("Answer with the shortest correct identifier or phrase. No explanation. "
                              f"If unknown: unknown.\n{case['question']}\n{row['excerpt']}")
                    start = perf_counter()
                    row.update(read_answer("codex", "gpt-6-luna", prompt, "low"), model_called=True)
                    model_ms = (perf_counter() - start) * 1000
                row["wall_ms"] = round(lookup_ms + model_ms, 3)
                row["page_hit"] = row["page"] == case["page"]
                anchors = case.get("anchors") or ([case["anchor"]] if case.get("anchor") else [])
                row["anchor_hit"] = (row["page_hit"] and row["anchor"] in anchors) if anchors else None
                row["page_evidence_hit"] = bool(row["page_hit"] and re.search(case["evidence"], row["excerpt"], re.I | re.S))
                row["evidence_hit"] = row["page_evidence_hit"] and row["anchor_hit"] is not False
                required = [case["answer"], *case.get("answer_all", [])]
                row["correct"] = (bool(row["evidence_hit"] and all(re.search(pattern, row["answer"], re.I)
                                      for pattern in required)) if row["answer"] is not None else None)
                rows.append(row)
                report["metrics"] = {a: summarize([r for r in rows if r["route"] == a], resources)
                                     for a in arms if any(r["route"] == a for r in rows)}
                temp = args.output.with_suffix(".tmp")
                temp.write_text(json.dumps(report, indent=2) + "\n")
                temp.replace(args.output)
    print(json.dumps(report["metrics"], indent=2))


if __name__ == "__main__":
    main()
