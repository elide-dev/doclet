"""Rebuild the direct-type-link treatment from a generated module snapshot."""
import argparse
from collections import defaultdict
from html import escape
from pathlib import Path, PurePosixPath
import re
import shutil
from urllib.parse import unquote, urlsplit


def type_directory(source):
    # Use the published list of types, including nested ones, as the routing table.
    groups = defaultdict(list)
    index = (source / "types.md").read_text(encoding="utf-8")
    for href in re.findall(r"^- \[.*?\]\(([^)]+)\), ", index, flags=re.MULTILINE):
        url = urlsplit(href)
        path = PurePosixPath(unquote(url.path))
        if (url.scheme or url.netloc or url.query or url.fragment or path.is_absolute()
                or ".." in path.parts or path.suffix != ".md" or not (source / path).is_file()):
            raise ValueError(f"Expected a published local type page, got {href}")
        package = ".".join(path.parent.parts)
        groups[package].append((path.stem, href))
    if not groups:
        raise ValueError("No type rows found in types.md")

    parts = ['<nav class="section" id="type-pages" aria-label="Type references in Markdown">'
             '<h2>Type references in Markdown</h2>']
    for package, entries in sorted(groups.items()):
        parts.append(f'<h3>{escape(package) or "(default package)"}</h3><p>')
        parts.append(' · '.join(f'<a href="{escape(url, quote=True)}">{escape(name)}</a>'
                                for name, url in sorted(entries)))
        parts.append("</p>")
    parts.append("</nav>")
    return "".join(parts), sum(map(len, groups.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Generated module with Markdown twins")
    parser.add_argument("target", type=Path, help="New sibling snapshot directory")
    args = parser.parse_args()
    source = args.source.resolve()
    target = args.target.resolve()
    if target.exists() or source in target.parents:
        parser.error("Choose a new target outside the source directory")

    directory, count = type_directory(source)
    html = (source / "index.html").read_text(encoding="utf-8")
    head = '<link rel="index" type="text/markdown" href="index-all.md"'
    body = '<section id="packages"'
    if html.count(head) != 1 or html.count(body) != 1 or 'id="type-pages"' in html:
        parser.error("Expected an unmodified generated module landing page with a Markdown declaration index")

    hint = ('<link rel="index" type="text/html" href="#type-pages" '
            'title="Find a declaring type on this page, then open its Markdown reference."/>')
    html = html.replace(head, hint + head).replace(body, directory + body)
    shutil.copytree(source, target)
    (target / "index.html").write_text(html, encoding="utf-8")
    print(f"{count} type links in {target / 'index.html'}")


if __name__ == "__main__":
    main()
