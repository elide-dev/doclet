"""Paired retrieval and optional answer evaluation on the public Java sample."""
import argparse
from collections import Counter
from html.parser import HTMLParser
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


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


def words(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def rank(question, index):
    query = set(words(question))
    docs = {name: Counter(words(entry)) for name, entry in index.items()}
    freq = Counter(term for doc in docs.values() for term in doc)
    def score(name):
        return sum(math.log1p(len(docs) / freq[t]) * (1 + math.log(docs[name][t]))
                   for t in sorted(query.intersection(docs[name])))
    return sorted(docs, key=lambda name: (-score(name), name))


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
    for discovery, index in indexes.items():
        for fmt in ["html", "markdown"]:
            for case in questions:
                chosen = rank(case["question"], index)[0]
                relative = Path("example/runtime") / chosen
                if fmt == "html":
                    body = visible((standard / (str(relative) + ".html")).read_text(), True)
                else:
                    body = (module / (str(relative) + ".md")).read_text()
                context = body[:args.chars]
                hit = chosen == case["page"]
                evidence = bool(hit and re.search(case["evidence"], context, re.I | re.S))
                row = dict(index=discovery, format=fmt, question=case["question"], page=chosen,
                           page_hit=hit, evidence_hit=evidence, context_chars=len(context))
                if args.reader:
                    prompt = f"Answer briefly using only this document. If unknown, say unknown.\nQuestion: {case['question']}\nDocument:\n{context}"
                    answer = subprocess.run(shlex.split(args.reader), input=prompt, text=True,
                                            capture_output=True, timeout=90, check=True).stdout.strip()
                    row.update(answer=answer, grounded_correct=bool(evidence and
                               re.search(case["answer"], answer, re.I)))
                rows.append(row)
    report = dict(character_budget=args.chars, reader=args.reader_label, results=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    for index in indexes:
        for fmt in ("html", "markdown"):
            group = [r for r in rows if (r["index"], r["format"]) == (index, fmt)]
            metrics = ["page_hit", "evidence_hit"] + (["grounded_correct"] if args.reader else [])
            print(index, fmt, *(f"{k}={sum(r[k] for r in group)}/{len(group)}" for k in metrics),
                  "mean context chars=", round(sum(r["context_chars"] for r in group) / len(group)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chars", type=int, default=4000)
    parser.add_argument("--reader", help="Optional model wrapper command: prompt on stdin, answer on stdout")
    parser.add_argument("--reader-label", help="Model/version and settings to record")
    parser.add_argument("--output", type=Path, default=Path(".dev/quality/doc-evals.json"))
    args = parser.parse_args()
    if args.chars <= 0 or bool(args.reader) != bool(args.reader_label):
        parser.error("Provide a positive budget and pair --reader with --reader-label")
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["javadoc", "-quiet", "-notimestamp", "-notree", "-d", tmp,
                        *sorted(Path("samples/java").rglob("*.java"))], check=True)
        evaluate(args, Path(tmp))
