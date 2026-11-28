"""Print the ranked source draw without opening generated documentation."""
import hashlib
import re
import sys
from pathlib import Path
from zipfile import ZipFile

COORDINATE = "com/beust/jcommander/1.78/jcommander-1.78-sources.jar"
SEED = "2026-11-27-jcommander"
DECLARATION = re.compile(
    r"/\*\*((?:(?!\*/).)*)\*/\s*(?:@[^\n]+\s+)*public\s+"
    r"([^\n;{}]+?\([^;{}]*?\))\s*(?:throws [^{};]+)?[{;]",
    re.S,
)


def draw(source):
    rows = []
    with ZipFile(source) as archive:
        for filename in sorted(archive.namelist()):
            if (not filename.startswith("com/beust/jcommander/")
                    or not filename.endswith(".java") or "/internal/" in filename):
                continue
            for doc, declaration in DECLARATION.findall(archive.read(filename).decode()):
                doc = re.sub(r"^\s*\*\s?", "", doc, flags=re.M).strip()
                if len(re.sub(r"\{@.*?\}", "", doc).split()) < 10:
                    continue
                declaration = " ".join(declaration.split())
                score = hashlib.sha256(f"{SEED}:{filename}:{declaration}".encode()).hexdigest()
                rows.append((score, filename, declaration))
    for rank, (_, filename, declaration) in enumerate(sorted(rows), 1):
        print(f"{rank}\t{filename}\t{declaration}")


if __name__ == "__main__":
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".dev/dependencies/m2", COORDINATE)
    draw(source)
