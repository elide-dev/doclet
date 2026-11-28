"""Summarize local model-directed discovery archives from eval-browse.py."""
import argparse
import json
from pathlib import Path
from statistics import mean


COUNTERS = ("observation_tokens", "input_tokens", "cached_input_tokens", "output_tokens")


def validate_trial(row):
    trace = row["trace"]
    if not trace or len(trace) != row["model_turns"]:
        raise ValueError("Incomplete session transcript")
    for key in COUNTERS:
        values = [step[key] for step in trace]
        if any(type(value) is not int or value < 0 for value in values) or sum(values) != row[key]:
            raise ValueError(f"Session {key} does not equal its per-turn deltas")
    if any(step["cached_input_tokens"] > step["input_tokens"] for step in trace):
        raise ValueError("Cached input exceeds provider input")
    index = row["first_evidence_after_actions"]
    if index is not None and (type(index) is not int or not 0 <= index < len(trace)):
        raise ValueError("First evidence lies outside the transcript")
    if row["fetches"] != len(row["requests"]) or row["bytes"] != sum(r["bytes"] for r in row["requests"]):
        raise ValueError("Session fetch or byte total disagrees with its requests")


def paired_trials(report, partial=False):
    if report["protocol"] != "model-directed HTML navigation":
        raise ValueError("Expected an eval-browse.py discovery archive")
    arms, cases = report["order"], report["cases"]
    if not cases or not arms or len(set(arms)) != len(arms) or len(set(cases)) != len(cases):
        raise ValueError("Expected nonempty, unique arms and cases")
    trials = {}
    questions = {}
    for row in report["results"]:
        case, site = row["case"], row["site"]
        if case not in cases or site not in arms or (case, site) in trials:
            raise ValueError("Unexpected or duplicate case and site")
        if questions.setdefault(case, row["question"]) != row["question"]:
            raise ValueError(f"Question mismatch on case {case}")
        validate_trial(row)
        trials[case, site] = row

    paired = [case for case in cases if all((case, site) in trials for site in arms)]
    if len(paired) != len(cases) and not partial:
        raise ValueError(f"Only {len(paired)}/{len(cases)} cases complete across all arms; use --partial")
    if not paired:
        raise ValueError("No complete paired cases")
    return paired, {site: [trials[case, site] for case in paired] for site in arms}


def trial_metrics(rows):
    reached = [r["first_evidence_after_actions"] for r in rows if r["first_evidence_after_actions"] is not None]
    grounded = [r["first_evidence_after_actions"] for r in rows if r["correct"]
                and r["first_evidence_after_actions"] is not None]
    charged_steps = []
    charged_visible = 0
    for row in rows:
        index = row["first_evidence_after_actions"]
        if index is None:
            index = len(row["trace"]) - 1
        charged_steps.append(index)
        charged_visible += sum(step["observation_tokens"] for step in row["trace"][:index + 1])
    result = {key: sum(row[key] for row in rows) for key in (*COUNTERS, "fetches", "bytes", "model_turns")}
    result.update(grounded=sum(r["correct"] for r in rows), reached=len(reached),
                  mean_reached=mean(reached) if reached else None,
                  mean_grounded=mean(grounded) if grounded else None, mean_charged=mean(charged_steps),
                  charged_visible=charged_visible, seconds=sum(r["wall_ms"] for r in rows) / 1000)
    return result


def cell(value):
    return str(value).replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")


def path_actions(row):
    steps = []
    for step in row["trace"]:
        cmd = step.get("command", {})
        act = cmd.get("action", "invalid")
        if act == "open":
            act += " L" + str(cmd["link"])
        if "query" in cmd:
            act += " " + json.dumps(cmd["query"], ensure_ascii=False)
        if step.get("error"):
            act += " (error)"
        path = step["observation"].get("url", "browser")
        steps.append(f"{path} → {act}")
    return "; ".join(steps)


def markdown(report, partial=False, traces=False):
    paired, by_site = paired_trials(report, partial)
    stats = {site: trial_metrics(rows) for site, rows in by_site.items()}
    arms = list(stats)
    lines = [f"**{len(paired)}/{len(report['cases'])} cases complete across all {len(arms)} arms.**",
             "", "| Measure | " + " | ".join(map(cell, arms)) + " |",
             "| --- | " + " | ".join("---:" for _ in arms) + " |"]
    measures = [
        ("Grounded answers", "grounded"), ("Reached supporting evidence", "reached"),
        ("Mean actions to evidence, reached cases", "mean_reached"),
        ("Mean actions to evidence, grounded cases", "mean_grounded"),
        ("Mean actions to evidence, misses charged", "mean_charged"),
        ("Visible tokens to evidence, misses charged", "charged_visible"),
        ("All visible observation tokens", "observation_tokens"), ("Luna turns", "model_turns"),
        ("Provider input tokens", "input_tokens"), ("Cached input tokens", "cached_input_tokens"),
        ("Provider output tokens", "output_tokens"), ("Fetches", "fetches"), ("Bytes fetched", "bytes"),
        ("Wall seconds", "seconds")]
    for label, key in measures:
        values = [stats[site][key] for site in arms]
        fmt = ".2f" if key.startswith("mean_") or key == "seconds" else ",d"
        lines.append(f"| {label} | " + " | ".join("—" if value is None else format(value, fmt)
                                                  for value in values) + " |")
    lines.extend(["", "The entry page costs zero navigation actions. Misses contribute every observed "
                  "attempt before the last turn and the full session’s displayed tokens. Hits include the "
                  "screen with the first supporting evidence. Repeated screens count each time they are "
                  "shown. Cached input is included in provider input."])
    if traces:
        lines.extend(["", "| Case | Arm | Outcome | First evidence after | Path and action on each turn |",
                      "| ---: | --- | --- | ---: | --- |"])
        for index, case in enumerate(paired):
            for site in arms:
                row = by_site[site][index]
                first = row["first_evidence_after_actions"]
                outcome = "grounded" if row["correct"] else row["stop"] + ", ungrounded"
                lines.append(f"| {case} | {cell(site)} | {cell(outcome)} | "
                             f"{first if first is not None else 'miss'} | {cell(path_actions(row))} |")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--partial", action="store_true", help="Compare only fully paired cases in an unfinished archive")
    parser.add_argument("--traces", action="store_true", help="Include the chosen navigation path for each trial")
    args = parser.parse_args()
    try:
        print(markdown(json.loads(args.report.read_text()), args.partial, args.traces))
    except (KeyError, OSError, TypeError, ValueError) as error:
        parser.exit(2, f"{parser.prog}: {error}\n")


if __name__ == "__main__":
    main()
