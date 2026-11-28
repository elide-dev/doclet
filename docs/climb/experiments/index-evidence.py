"""Enrich the existing alphabetical Markdown index with bounded, documented member contracts."""
import argparse
from collections import Counter
import json
from pathlib import Path, PurePosixPath
import re
import shutil
from urllib.parse import unquote, urlsplit


BUDGET = 320
MORE = "Full details in the linked declaration."
ROW = re.compile(r"^- \[`[^`\n]+`\]\(([^\s)]+)\), ")
MARKER = re.compile(r"^<!-- ([^\n]+) -->\n", re.MULTILINE)
SEPARATOR = " · "


def key(href):
    url = urlsplit(href)
    path = PurePosixPath(unquote(url.path))
    if url.scheme or url.netloc or path.is_absolute() or ".." in path.parts or not url.fragment:
        return None
    return path.with_suffix(".md").as_posix(), unquote(url.fragment)


def prose(value):
    # Contract JSON contains plain text. Keep strings literal in the Markdown row.
    text = " ".join(value.split())
    return re.sub(r"([\\`*_\[\]<>|])", r"\\\1", text)


def code(value):
    # The published renderer uses code spans for type names.
    return "`" + value + "`" if "`" not in value else prose(value)


def suffix(record):
    fields = []
    dep = record.get("deprecated") or {}
    if dep.get("doc"):
        fields.append("Deprecated: " + prose(dep["doc"]))
    for error in record.get("throws", []):
        if error.get("type"):
            fields.append("Throws " + code(error["type"]) + (": " + prose(error["doc"]) if error.get("doc") else ""))
    returns = record.get("returns") or {}
    if returns.get("type"):
        fields.append("Returns " + code(returns["type"]) + (": " + prose(returns["doc"]) if returns.get("doc") else ""))
    if record.get("details"):
        fields.append(prose(record["details"]))

    # Keep each entire field, including any qualifications in a later sentence.
    # Reserve the pointer before choosing fields if anything will be left at the declaration.
    omitted = record.get("detailsOmitted") or sum(len(SEPARATOR + field) for field in fields) > BUDGET
    remaining = BUDGET - (len(SEPARATOR + MORE) if omitted else 0)
    selected = []
    for field in fields:
        cost = len(SEPARATOR + field)
        if cost <= remaining:
            selected.append(field)
            remaining -= cost
    if omitted:
        selected.append(MORE)
    return "".join(SEPARATOR + field for field in selected)


def enrich(source, additions):
    rows = []
    seen = Counter()
    for line in source.splitlines(keepends=True):
        match = ROW.match(line)
        member = key(match[1]) if match else None
        if member in additions:
            seen[member] += 1
            core = line.rstrip("\r\n")
            line = core + additions[member] + line[len(core):]
        rows.append(line)
    return "".join(rows), seen


def build(source, target):
    if target.exists() or source == target or source in target.parents:
        raise ValueError("Choose a new target outside the source directory")
    additions = {}
    for line in (source / "contracts.ndjson").read_text().splitlines():
        record = json.loads(line)
        member = key(record["href"])
        if member:
            if member in additions:
                raise ValueError(f"Conflicting records for {member}")
            if not (source / member[0]).is_file():
                raise ValueError(f"Missing Markdown declaration {member}")
            additions[member] = suffix(record)
    index = (source / "index-all.md").read_text()
    enriched, seen = enrich(index, additions)
    if not seen or any(n != 1 for n in seen.values()):
        raise ValueError("Expected unique index rows matching canonical member hrefs")

    # The global index is at the bundle's own root. Preserve its published links and
    # append plain contract fields only in that section, when the bundle includes it.
    bundle = (source / "llms-full.txt").read_text()
    parts = list(MARKER.finditer(bundle))
    for i, marker in enumerate(parts):
        if marker[1] == "index-all.md":
            end = parts[i + 1].start() if i + 1 < len(parts) else len(bundle)
            section, merged = enrich(bundle[marker.end():end], additions)
            if merged != seen:
                raise ValueError("Bundle contains a different set of alphabetical declarations")
            bundle = bundle[:marker.end()] + section + bundle[end:]
            break

    shutil.copytree(source, target)
    (target / "index-all.md").write_text(enriched)
    if bundle != (source / "llms-full.txt").read_text():
        (target / "llms-full.txt").write_text(bundle)
    print(f"{sum(bool(additions[k]) for k in seen)}/{len(seen)} member rows enriched; "
          f"{len(index.encode()):,} → {len(enriched.encode()):,} index bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    build(args.source.resolve(), args.target.resolve())
