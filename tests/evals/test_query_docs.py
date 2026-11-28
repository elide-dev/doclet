"""Exercise the published HTML starting point through the command-line interface."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "query_docs.py"
QUESTION = "What does Value.read(String) return?"


class QueryDocsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.page = self.root / "p" / "Value.html"
        self.page.parent.mkdir()
        (self.root / "contracts.ndjson").write_text(json.dumps({
            "href": "p/Value.html#read(java.lang.String)",
            "name": "read", "returns": {"doc": "a string"},
        }) + "\n")

    def query(self, entry, *options):
        result = subprocess.run([sys.executable, str(SCRIPT), str(entry), QUESTION, *options],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_resolves_nested_file_under_explicit_site_root(self):
        self.page.write_text('<link rel="describedby" href="../contracts.ndjson">')
        result = self.query(self.page, "--site-root", str(self.root))
        self.assertEqual(result["anchor"], "read(java.lang.String)")
        self.assertEqual(result["extraction"]["answer"], "a string")

    def test_reads_preexisting_llms_discovery_link(self):
        (self.root / "index.html").write_text('<a href="llms.txt">llms.txt</a>')
        (self.root / "llms.txt").write_text("- [Declaration contracts](contracts.ndjson)\n")
        result = self.query(self.root, "--html-entry")
        self.assertEqual(result["extraction"]["answer"], "a string")
        self.assertEqual([request["path"] for request in result["requests"]],
                         ["index.html", "llms.txt", "contracts.ndjson"])

    def test_does_not_follow_external_link_from_local_page(self):
        (self.root / "index.html").write_text(
            '<link rel="describedby" href="https://docs.example.test/contracts.ndjson">')
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.root), QUESTION, "--html-entry"],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("escaped local origin", result.stderr)
