"""Measure local member-index construction from warm files, with alternating order."""
import argparse
import json
from pathlib import Path
from statistics import median
from time import perf_counter

from query_docs import Reference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("javadoc", type=Path)
    parser.add_argument("markdown", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    roots = {"html": args.javadoc, "markdown": args.markdown}
    timings = {fmt: [] for fmt in roots}
    sizes = {}
    for trial in range(21):
        for fmt in list(roots)[::(-1 if trial % 2 else 1)]:
            start = perf_counter()
            reference = Reference(roots[fmt], fmt)
            elapsed = (perf_counter() - start) * 1000
            sizes[fmt] = len(reference.members)
            if trial:
                timings[fmt].append(round(elapsed, 3))
            del reference
    report = dict(declarations=sizes, trials_ms=timings,
                  median_ms={fmt: median(times) for fmt, times in timings.items()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(report["median_ms"])


if __name__ == "__main__":
    main()
