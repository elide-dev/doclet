"""Local passage and Markdown layout experiments on the same retrieved type."""
import re
from html.parser import HTMLParser
from eval_discovery import _overloads, glance_rows


class MemberDetails(HTMLParser):
    """Extract the text of each standard Javadoc member-detail section."""
    def __init__(self):
        super().__init__()
        self.sections = []
        self.rows = []
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "section":
            detail = "detail" in dict(attrs).get("class", "").split()
            self.sections.append(detail)

    def handle_endtag(self, tag):
        if tag == "section" and self.sections and self.sections.pop():
            self.rows.append(" ".join(" ".join(self.parts).split()))
            self.parts = []

    def handle_data(self, data):
        if any(self.sections):
            self.parts.append(data)


def passage(source, fmt, layout, question, type_name, rank, count):
    exact = set()
    if fmt == "html":
        parser = MemberDetails()
        parser.feed(source)
        rows = parser.rows
    else:
        entries = list(glance_rows(source, type_name))
        rows = [row for _, row in entries]
        signatures = set(_overloads(question, [member for member, _ in entries if member.anchor]))
        exact = {row for member, row in entries if member in signatures}
    if not rows:
        return None
    ranked = rank(question, {row: row for row in rows})
    # A qualified member in the question is a direct lookup in either format.
    names = re.findall(r"\b" + re.escape(type_name) + r"\.(\w+)", question)
    if names:
        ranked.sort(key=lambda row: row.split("(", 1)[0].split(":", 1)[0].split()[0] not in names)
    if exact:
        ranked.sort(key=lambda row: row not in exact)
    ranked = ranked[:count]
    if fmt == "html":
        return "\n".join(ranked)
    if layout == "table":
        return "|Member|Contract|\n|---|---|\n" + "\n".join(
            "|" + row.replace(": ", "|", 1) + "|" for row in ranked)
    if layout == "symbols":
        ranked = [row.replace("Returns ", "→ ").replace("Throws ", "⚠️ ").replace("Deprecated.", "⛔")
                  for row in ranked]
    return "\n".join("- " + row for row in ranked)
