"""Run one local account turn per arm over an offline paired excerpt report."""
import argparse
from hashlib import sha256
import json
import re
from pathlib import Path
from time import perf_counter

from eval_accounts import read_answer
from eval_packet import parse_packet_reply


def _packet(row):
    candidates = row.get("candidates") or []
    if len(candidates) < 2:
        return []
    expected = "\n".join(f"[{candidate['id']}] {candidate['excerpt']}" for candidate in candidates)
    if row.get("packet") != expected:
        raise ValueError("Multi-candidate replay must preserve the recorded numbered evidence")
    if row.get("extraction"):
        raise ValueError("A multi-candidate row must use its recorded packet for citation")
    return candidates


def _reference(row):
    return {key: row.get(key) for key in ("page", "path", "anchor", "excerpt")}


def _score(case, citation, answer):
    if not citation or citation["page"] != case["page"]:
        return False
    anchors = case.get("anchors") or ([case["anchor"]] if case.get("anchor") else [])
    return (not anchors or citation["anchor"] in anchors) and bool(
        re.search(case["evidence"], citation["excerpt"] or "", re.I | re.S)
        and all(re.search(pattern, answer, re.I)
                for pattern in [case["answer"], *case.get("answer_all", [])]))


def replay_rows(rows, fixtures, model):
    """Answer from saved evidence with one model call, preserving the chosen citation."""
    packets = [_packet(row) for row in rows]
    tasks = {}
    for i, (row, candidates) in enumerate(zip(rows, packets)):
        document = row["packet"] if candidates else row["excerpt"]
        if document.strip() and not row.get("extraction"):
            tasks[str(i)] = dict(question=row["question"], document=document)
            if candidates:
                tasks[str(i)]["cite"] = True
    usage = dict(input_tokens=0, cached_input_tokens=0, output_tokens=0)
    elapsed = 0
    replies = {}
    if tasks:
        schema = dict(type="object", properties={key: {"type": "string"} for key in tasks},
                      required=list(tasks), additionalProperties=False)
        prompt = ("Answer each numbered question with the shortest correct phrase in its JSON field. "
                  "Use only that item's document. Write unknown if insufficient.")
        if any(packets):
            prompt += " For items marked cite, reply as [id] answer using exactly one numbered excerpt."
        start = perf_counter()
        usage = read_answer("codex", model, prompt + "\n" + json.dumps(tasks), "low", output_schema=schema)
        elapsed = round(1000 * (perf_counter() - start), 2)
        replies = json.loads(usage.pop("answer").strip())
        if (not isinstance(replies, dict) or replies.keys() != tasks.keys()
                or not all(isinstance(answer, str) for answer in replies.values())):
            raise ValueError("Expected one answer string per fallback")

    audit = []
    for i, (row, case, candidates) in enumerate(zip(rows, fixtures, packets)):
        raw = replies.get(str(i))
        if candidates:
            citation, answer = parse_packet_reply(raw or "unknown", candidates)
        elif row.get("extraction"):
            citation = _reference(row)
            answer = row["extraction"]["answer"]
        elif raw and raw.strip() and raw.strip().lower().rstrip(".!") != "unknown":
            citation, answer = _reference(row), raw.strip()
        else:
            citation, answer = None, "unknown"
        item = dict(question=row["question"], answer=answer, citation=citation, raw_reply=raw,
                    lookup_ms=row.get("lookup_ms"), correct=_score(case, citation, answer))
        if candidates:
            item.update(packet=row["packet"], candidates=candidates)
        else:
            item["evidence"] = _reference(row)
        audit.append(item)
    return audit, usage, elapsed, len(tasks)


def html_entry_batches(args, source, model):
    """Replay recorded HTML-entry evidence, batching model fallbacks."""
    if not args.questions or sha256(args.questions.read_bytes()).hexdigest() != source["fixtures"]:
        raise ValueError("Supply --questions from the source report, with the same fixture checksum")
    if args.account != "codex" or model != "gpt-6-luna":
        raise ValueError("HTML-entry batch comparisons use the same Luna low reader as the independent turns")
    fixtures = json.loads(args.questions.read_text())
    arms = source["routes"]
    result = dict(source=args.report.name, source_sha256=sha256(args.report.read_bytes()).hexdigest(),
                  fixtures=source["fixtures"], model=model, effort="low", order_offset=args.offset,
                  protocol="one schema-constrained turn per arm; multi rows cite one evidence ID", arms=[])
    offset = args.offset % len(arms)
    for arm in arms[offset:] + arms[:offset]:
        rows = [r for r in source["results"] if r["route"] == arm]
        if [r["question"] for r in rows] != [q["question"] for q in fixtures]:
            raise ValueError("Each route must have one row per question, in fixture order")
        audit, usage, elapsed, fallback_questions = replay_rows(rows, fixtures, model)
        answers = [item["answer"] for item in audit]
        scores = [item["correct"] for item in audit]
        lookup_ms = round(sum(r["lookup_ms"] for r in rows), 2)
        result["arms"].append(dict(route=arm, correct=sum(scores), scores=scores, answers=answers,
                                   questions=audit, calls=int(bool(fallback_questions)),
                                   fallback_questions=fallback_questions, model_ms=elapsed,
                                   source_lookup_ms=lookup_ms,
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
