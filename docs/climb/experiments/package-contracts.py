"""Build the package-contract discovery ablation from a generated site."""
import argparse
from collections import defaultdict
from html import escape
import json
from pathlib import Path, PurePosixPath
import re
from shutil import copytree
from urllib.parse import quote, unquote, urlsplit


def prose(value):
    return " ".join(value.split()) if value else ""


def inline_text(nodes):
    return "".join(node.get("text", "") or inline_text(node.get("children", [])) for node in nodes)


def contract_line(record, package, target):
    name = record["symbol"].removeprefix(package + ".")
    fence = "`" * (1 + max((len(m[0]) for m in re.finditer(r"`+", name)), default=0))
    fields = [record.get("summary"), record.get("details")]
    for param in record.get("params", []):
        if param.get("doc"):
            fields.append(f"{param['name']}: {param['doc']}")
    returns = record.get("returns")
    if returns and returns.get("doc"):
        fields.append("Returns " + returns["doc"])
    for thrown in record.get("throws", []):
        fields.append("Throws " + thrown["type"] + (": " + thrown["doc"] if thrown.get("doc") else ""))
    deprecated = record.get("deprecated")
    if deprecated:
        fields.append("Deprecated" + (": " + deprecated["doc"] if deprecated.get("doc") else ""))
    if record.get("detailsOmitted"):
        fields.append("See declaration for details.")
    text = " ".join(dict.fromkeys(prose(value) for value in fields if value))
    text = re.sub(r"([\\`*_\[\]<>])", r"\\\1", text)
    return f"- [{fence}{name}{fence}]({target})" + (f" — {text}" if text else "")


def build(source, destination):
    if source == destination or source in destination.parents or destination.exists():
        raise ValueError("Choose a new destination outside the source directory")
    model = json.loads((source / "api.json").read_text())
    packages = {package["name"]: package for package in model["packages"]}
    by_package = defaultdict(list)
    for line in (source / "contracts.ndjson").read_text().splitlines():
        record = json.loads(line)
        url = urlsplit(record["href"])
        path = PurePosixPath(unquote(url.path))
        package = ".".join(path.parts[:-1])
        if (url.scheme or url.netloc or url.query or path.is_absolute() or ".." in path.parts
                or package not in packages or path.suffix != ".html"):
            raise ValueError(f"Unexpected declaration path {path}")
        fragment = "#" + quote(unquote(url.fragment), safe=".,-$") if url.fragment else ""
        target = quote(path.with_suffix(".md").name) + fragment
        if not (source / path.with_suffix(".md")).is_file():
            raise ValueError(f"Missing Markdown twin for {path}")
        by_package[package].append(contract_line(record, package, target))

    copytree(source, destination)
    links = []
    for name, rows in by_package.items():
        file = PurePosixPath(*name.split(".")) / "-members.md"
        path = destination / file
        with path.open("x") as writer:
            writer.write(f"# {name or 'Unnamed package'}\n\n" + "\n".join(rows) + "\n")
        summary = prose(inline_text((packages[name].get("doc") or {}).get("summary", [])))
        title = f"Package API contracts. {summary}".rstrip()
        links.append(f'<link rel="index" type="text/markdown" href="{escape(file.as_posix())}" title="{escape(title)}"/>')
    index = destination / "index.html"
    html = index.read_text()
    before = '<link rel="index" type="text/markdown" href="index-all.md"'
    if html.count(before) != 1:
        raise ValueError("Expected one global alphabetical Markdown index link")
    index.write_text(html.replace(before, "".join(links) + before))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    build(args.source.resolve(), args.destination.resolve())
