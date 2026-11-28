import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_navigation import Navigator


class NavigationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "site"
        self.root.mkdir()

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
        return len(text.encode())

    def html(self, content, head=""):
        return f"<html><head>{head}</head><body>{content}</body></html>"

    def link(self, view, text):
        for line in view["text"].splitlines():
            if text in line and (link := re.search(r'\[L(\d+)\]', line)):
                return int(link[1])
        self.fail(f"no link for {text} in {view}")

    def test_only_shown_links_and_citations_are_available(self):
        self.write("index.html", self.html('<p>start</p>' * 50 + '<p><a href="late.md">Late</a></p>'))
        self.write("late.md", "ABC\n" + "x " * 190 + "unseen detail")
        nav = Navigator(self.root, window_chars=160)
        view = nav.start()
        with self.assertRaisesRegex(ValueError, "shown"):
            nav.act(dict(action="open", link=1))
        self.assertIsNone(nav.citation(view["evidence_ids"][-1] + 1))
        found = nav.act(dict(action="find", query="Late"))
        view = nav.act(dict(action="open", link=self.link(found, "Late")))
        self.assertTrue(view["more"])
        for identifier in view["evidence_ids"]:
            citation = nav.citation(identifier)
            self.assertIn(citation["excerpt"], view["text"])
            self.assertNotIn("unseen detail", citation["excerpt"])
        fetched = len(nav.requests)
        while view["more"]:
            view = nav.act(dict(action="next"))
        self.assertIn("unseen detail", view["text"])
        self.assertEqual(fetched, len(nav.requests))

    def test_long_url_still_fits_window_including_header(self):
        label = "P" * 110 + ".md"
        self.write("index.html", self.html(f'<a href="{label}">Article</a>'))
        self.write(label, ("long article text " * 100).strip())
        nav = Navigator(self.root, window_chars=160)
        view = nav.act(dict(action="open", link=self.link(nav.start(), "Article")))
        while True:
            self.assertLessEqual(len(view["text"]), 160)
            self.assertTrue(view["evidence_ids"])
            if not view["more"]:
                break
            view = nav.act(dict(action="next"))

    def test_meta_redirect_charges_both_reads_and_stays_inside_site(self):
        a = self.write("index.html", self.html("", '<meta http-equiv="Refresh" content="0;pkg/package-summary.html">'))
        b = self.write("pkg/package-summary.html", self.html("<h1>Package</h1>"))
        nav = Navigator(self.root)
        view = nav.start()
        self.assertEqual(view["url"], "pkg/package-summary.html")
        self.assertEqual(nav.requests, [dict(path="index.html", bytes=a), dict(path="pkg/package-summary.html", bytes=b)])
        self.assertEqual(nav.bytes, a + b)
        self.write("index.html", self.html("", '<meta http-equiv="Refresh" content="0;url=index.html">'))
        with self.assertRaisesRegex(ValueError, "Circular"):
            Navigator(self.root).start()

    def test_indexes_have_real_exact_citations_and_anchor_scroll(self):
        self.write("index.html", self.html('<a href="index-all.html">Index</a>'))
        self.write("index-all.html", self.html('''
        <dl class="index"><dt><a href="x/Foo.html#get(int)" class="member-name-link">get(int)</a></dt>
        <dd>The value for this index.</dd></dl>'''))
        self.write("x/Foo.html", self.html('''
          <div class="summary-table"><div class="col-first">int</div>
          <div class="col-second"><a class="member-name-link" href="#get(int)">get</a></div>
          <div class="col-last">Summary description.</div></div>
          <section class="detail" id="get(int)"><h3>get</h3><p>The full contract returns this index.</p></section>'''))
        nav = Navigator(self.root)
        home = nav.start()
        index = nav.act(dict(action="open", link=self.link(home, "Index"), query="value"))
        cite = nav.citation(index["evidence_ids"][0])
        self.assertEqual((cite["page"], cite["anchor"]), ("Foo", "get(int)"))
        self.assertIn("The value for this index.", cite["excerpt"])
        detail = nav.act(dict(action="open", link=self.link(index, "get(int)")))
        self.assertIn("full contract", detail["text"])
        self.assertNotIn("Summary description", detail["text"])
        self.assertEqual(nav.citation(detail["evidence_ids"][0])["anchor"], cite["anchor"])

    def test_markdown_and_contract_records_preserve_published_sources(self):
        self.write("index.html", self.html("", '<link rel="alternate" type="text/markdown" href="Foo.md" title="Markdown"><link rel="describedby" href="rows.ndjson" type="application/x-ndjson" title="Declarations">'))
        self.write("Foo.md", '### Read\n<a id="read(java.lang.String)"></a>\nReads the file.\n## Other\nDifferent section.')
        entry = dict(href="Foo.html#read(java.lang.String)", symbol="Foo.read(String)", summary="Reads the file.")
        self.write("rows.ndjson", json.dumps(entry))
        nav = Navigator(self.root)
        view = nav.start()
        md = nav.act(dict(action="open", link=self.link(view, "Markdown"), query="Reads"))
        self.assertEqual(nav.citation(md["evidence_ids"][0])["anchor"], "read(java.lang.String)")
        self.assertEqual(nav.citation(md["evidence_ids"][-1])["anchor"], "")
        contract = nav.act(dict(action="open", link=self.link(view, "Declarations"), query="Foo.read(String)"))
        cite = nav.citation(contract["evidence_ids"][0])
        self.assertIn(json.dumps(entry), cite["excerpt"])
        self.assertEqual((cite["path"], cite["anchor"]), ("Foo", "read(java.lang.String)"))

    def test_find_is_literal_and_uses_only_current_resource(self):
        self.write("index.html", self.html('<p>A_b</p><p>middle</p><p>a_B</p><a href="other.md">Other</a>'))
        self.write("other.md", "a.b")
        nav = Navigator(self.root)
        nav.start()
        a = nav.act(dict(action="find", query="a_b"))
        b = nav.act(dict(action="find", query="a_b"))
        self.assertIn("A_b", a["text"])
        self.assertNotIn("middle", b["text"])
        miss = nav.act(dict(action="find", query="a.b"))
        self.assertFalse(miss["evidence_ids"])
        self.assertEqual(len(nav.requests), 1)

    def test_native_javadoc_loader_and_type_search_index(self):
        self.write("index.html", self.html("", '<script src="script-files/script.js"></script>'))
        self.write("script-files/script.js", "createElem(doc, tag, 'type-search-index.js');")
        self.write("type-search-index.js", 'typeSearchIndex = [{"l":"Foo","p":"x"}];updateSearchResults();')
        self.write("x/Foo.html", self.html("<p>Foo API</p>"))
        nav = Navigator(self.root)
        home = nav.start()
        loader = nav.act(dict(action="open", link=self.link(home, "script-files")))
        index = nav.act(dict(action="open", link=self.link(loader, "type-search-index"), query="Foo"))
        foo = nav.act(dict(action="open", link=self.link(index, "Foo")))
        self.assertIn("Foo API", foo["text"])
        self.assertEqual(len(nav.requests), 4)

    def test_disallows_external_file_and_symlink_escape(self):
        outside = self.root.parent / "secret.md"
        outside.write_text("outside content")
        (self.root / "jump.md").symlink_to(outside)
        self.write("index.html", self.html('<p><a href="https://outside.test/x.md">external</a></p><p><a href="file:///secret.md">file</a></p><p><a href="jump.md">Jump</a></p>'))
        nav = Navigator(self.root)
        home = nav.start()
        self.assertEqual(home["text"].count("[L"), 1)
        with self.assertRaisesRegex(ValueError, "unavailable"):
            nav.act(dict(action="open", link=self.link(home, "Jump")))
        self.assertEqual(len(nav.requests), 1)


if __name__ == "__main__":
    unittest.main()
