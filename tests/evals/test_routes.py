"""Exercise local discovery using only advertised links."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from eval_routes import route
from eval_discovery import Member
from eval_evidence import markdown_detail, select_evidence


class Routes(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.directory = Path(self.directory.name)
        self.entry = (self.directory / "index.html").as_uri()

    def write(self, path, body):
        target = self.directory / path
        target.parent.mkdir(exist_ok=True, parents=True)
        target.write_text(body)

    def test_direct_route_selects_exact_overload_and_counts_only_actual_reads(self):
        self.write("index.html", """
            <link rel="alternate" type="text/markdown" href="llms.txt">
            <link rel="alternate" type="text/markdown" href="index.md">
            <link rel="describedby" type="application/x-ndjson" href="m/contracts.ndjson">""")
        self.write("m/contracts.ndjson", """
{"href":"p/Value.html#read(java.lang.String)","name":"read","returns":{"doc":"a string"}}
{"href":"p/Value.html#read(java.lang.Class)","name":"read","returns":{"doc":"a class"}}
""".strip())
        result = route(self.entry, "What does Value.read(Class) return?", "direct", encode=lambda s: s.split())
        self.assertEqual(result["anchor"], "read(java.lang.Class)")
        self.assertEqual(result["extraction"]["answer"], "a class")
        self.assertTrue(result["markdown_url"].endswith("/index.md"))
        self.assertEqual([r["path"] for r in result["requests"]], ["index.html", "m/contracts.ndjson"])
        self.assertEqual(result["fetches"], 2)
        self.assertEqual(result["discovery_tokens"], result["source_tokens"])

    def test_inline_honors_distinct_content_and_discovery_bytes(self):
        self.write("index.html", '<link rel="help" type="text/plain" href="llms.txt">')
        self.write("llms.txt", "- [Value (class in p)](p/Value.md): reads values\n")
        self.write("p/Value.md", "# Value\n\n## Methods\n\n### read(String)\n\nReturns: a string.\n")
        result = route(self.entry, "What does Value.read(String) return?", "inline", encode=lambda s: s.split())
        self.assertEqual(result["anchor"], "read(String)")
        self.assertEqual(result["fetches"], 3)
        self.assertLess(result["discovery_tokens"], result["source_tokens"])

    def test_fully_indexed_javadoc_follows_redirect_and_reads_every_type(self):
        self.write("index.html", '<link rel="canonical" href="p/package-summary.html">')
        self.write("p/package-summary.html", '<a href="../index-all.html">Index</a>')
        self.write("index-all.html", """
<dl class="index">
<dt><a class="member-name-link" href="p/Value.html#read(java.lang.Class)">read(Class)</a></dt><dd>Read value.</dd>
<dt><a class="type-name-link" href="p/Other.html">Other</a></dt><dd>Other type.</dd>
</dl>""")
        self.write("p/Value.html", '<section id="read(java.lang.Class)" class="detail"><h3>read</h3> Returns: a class</section>')
        self.write("p/Other.html", '<section id="class-description">Other type.</section>')
        result = route(self.entry, "What does Value.read(Class) return?", "javadoc")
        self.assertEqual(result["anchor"], "read(java.lang.Class)")
        self.assertEqual(result["fetches"], 5)
        self.assertIn("p/Other.html", [r["path"] for r in result["requests"]])
        lazy = route(self.entry, "What does Value.read(Class) return?", "javadoc-lazy")
        self.assertEqual(lazy["anchor"], result["anchor"])
        self.assertEqual(lazy["fetches"], 4)
        self.assertNotIn("p/Other.html", [r["path"] for r in lazy["requests"]])


    def test_javadoc_scoped_follows_native_package_type_links(self):
        self.write("index.html", '<link rel="canonical" href="p/package-summary.html">')
        self.write("p/package-summary.html", """
<a href="../index-all.html">Index</a>
<div class="class-summary col-first"><a href="Value.html">Value</a></div>
""")
        self.write("p/Value.html", """
<section class="detail" id="read(java.lang.String)"><h3>read</h3> Returns: a string</section>
<section class="detail" id="read(java.lang.Class)"><h3>read</h3> Returns: a class</section>
""")
        result = route(self.entry, "What does Value.read(Class) return?", "javadoc-scoped")
        self.assertEqual(result["anchor"], "read(java.lang.Class)")
        self.assertEqual(result["extraction"]["answer"], "a class")
        self.assertEqual([r["path"] for r in result["requests"]],
                         ["index.html", "p/package-summary.html", "p/Value.html"])

    def test_javadoc_scoped_reads_native_search_index_without_executing_javascript(self):
        self.write("index.html", """<script src="script-files/script.js"></script>
<a href="index-all.html">Index</a>""")
        self.write("script-files/script.js", "createElem(doc, tag, 'type-search-index.js');")
        self.write("type-search-index.js", """typeSearchIndex =
[{"p":"p","l":"Value"},{"p":"q","l":"Value"}];updateSearchResults();""")
        self.write("q/Value.html", """
<section class="detail" id="read()"><h3>read</h3> Returns: a class</section>
""")
        result = route(self.entry, "What does q.Value.read() return?", "javadoc-scoped")
        self.assertEqual(result["path"], "q/Value")
        self.assertEqual(result["extraction"]["answer"], "a class")
        self.assertEqual([r["path"] for r in result["requests"]],
                         ["index.html", "script-files/script.js", "type-search-index.js", "q/Value.html"])

    def test_javadoc_scoped_falls_back_to_lazy_index_when_type_is_not_named(self):
        self.write("index.html", '<a href="index-all.html">Index</a>')
        self.write("index-all.html", """
<dl class="index">
<dt><a class="member-name-link" href="p/Value.html#read()">read()</a></dt><dd>Reads a class.</dd>
</dl>""")
        self.write("p/Value.html", '<section id="read()" class="detail"><h3>read</h3> Returns: a class</section>')
        result = route(self.entry, "Which method reads a class?", "javadoc-scoped")
        self.assertEqual(result["anchor"], "read()")
        self.assertEqual([r["path"] for r in result["requests"]],
                         ["index.html", "index-all.html", "p/Value.html"])


    def test_alternative_packets_keep_the_primary_and_charge_actual_source_reads(self):
        self.write("index.html", '<link rel="describedby" href="contracts.ndjson"><a href="index-all.html">Index</a>')
        self.write("contracts.ndjson", """{"href":"p/Cache.html#read()","name":"read","summary":"Reads a cached value.","returns":{"doc":"the value from cache."}}
{"href":"p/Store.html#peek()","name":"peek","summary":"Reads a stored value.","returns":{"doc":"the value on disk."}}
{"href":"p/Stream.html#next()","name":"next","summary":"Reads a queued value.","returns":{"doc":"the value in queue."}}
""")
        self.write("index-all.html", """
<dl class="index">
<dt><a class="member-name-link" href="p/Cache.html#read()">read()</a></dt><dd>Reads a cached value.</dd>
<dt><a class="member-name-link" href="p/Store.html#peek()">peek()</a></dt><dd>Reads a stored value.</dd>
<dt><a class="member-name-link" href="p/Stream.html#next()">next()</a></dt><dd>Reads a queued value.</dd>
</dl>""")
        for path, member, description, value in [
            ("Cache", "read", "cached", "the value from cache."),
            ("Store", "peek", "stored", "the value on disk."),
            ("Stream", "next", "queued", "the value in queue."),
        ]:
            self.write(f"p/{path}.html",
                       f'<section class="detail" id="{member}()">{member}() Reads a {description} value. Returns: {value}</section>')
        for variant, fetches in [("scoped", 2), ("javadoc-adaptive", 5)]:
            with self.subTest(variant=variant):
                question = "Which method reads a value from a buffer?"
                original = route(self.entry, question, variant, chars=260)
                packet = route(self.entry, question, variant, chars=260, alternatives=3)
                self.assertNotIn("candidates", original)
                self.assertEqual(packet["path"], original["path"])
                self.assertEqual(packet["anchor"], original["anchor"])
                self.assertEqual(packet["candidates"][0]["anchor"], original["anchor"])
                self.assertEqual(len(packet["candidates"]), 3)
                self.assertEqual(len({(item["path"], item["anchor"]) for item in packet["candidates"]}), 3)
                self.assertTrue(all(item["excerpt"].endswith(".") for item in packet["candidates"]))
                self.assertLessEqual(len(packet["excerpt"]), 260)
                self.assertEqual(packet["fetches"], fetches)
                self.assertIsNone(packet["extraction"])



    def test_alternatives_apply_explicit_return_type_to_both_formats(self):
        self.write("index.html", '<link rel="describedby" href="contracts.ndjson"><a href="index-all.html">Index</a>')
        self.write("contracts.ndjson", """
{"href":"p/Buffer.html#describe()","name":"describe","summary":"Returns a formatted description of input bytes.","returns":{"type":"String","doc":"formatted description."}}
{"href":"p/Buffer.html#encode()","name":"encode","summary":"Formats the input.","returns":{"type":"byte[]","doc":"encoded payload."}}
""".strip())
        self.write("index-all.html", """
<dl class="index">
<dt><a class="member-name-link" href="p/Buffer.html#describe()">describe()</a></dt><dd>Returns a formatted description of input bytes.</dd>
<dt><a class="member-name-link" href="p/Buffer.html#encode()">encode()</a></dt><dd>Formats the input.</dd>
</dl>""")
        self.write("p/Buffer.html", """
<section class="detail" id="describe()"><h3>describe</h3><div class="member-signature">
<span class="return-type">String</span> describe()</div>Returns: a formatted description of input bytes.</section>
<section class="detail" id="encode()"><h3>encode</h3><div class="member-signature">
<span class="return-type">byte[]</span> encode()</div>Returns: encoded payload.</section>
""")
        for variant in ("scoped", "javadoc-adaptive"):
            with self.subTest(variant=variant):
                result = route(self.entry, "Which method on Buffer returns bytes for the formatted input?",
                               variant, alternatives=3)
                self.assertEqual(result["anchor"], "encode()")
                self.assertEqual([item["anchor"] for item in result["candidates"]], ["encode()"])
                self.assertIn("encoded payload.", result["excerpt"])

    def test_alternatives_preserve_complete_primary_when_partition_cannot_fit_it(self):
        self.write("index.html", '<link rel="describedby" href="contracts.ndjson">')
        self.write("contracts.ndjson", """{"href":"p/Temperature.html#max()","name":"max","summary":"Reports the maximum temperature in degrees Celsius measured by the probe during sampling."}
{"href":"p/Cache.html#peek()","name":"peek","summary":"Reads a cached value."}
{"href":"p/Stream.html#next()","name":"next","summary":"Reads a queued value."}
""")
        question = "Which method reports the maximum temperature in degrees Celsius?"
        original = route(self.entry, question, "scoped", chars=120)
        result = route(self.entry, question, "scoped", chars=120, alternatives=3)
        self.assertEqual(len(result["candidates"]), 1)
        self.assertEqual(result["anchor"], original["anchor"])
        self.assertEqual(result["excerpt"], original["excerpt"])
        self.assertIn("during sampling.", result["excerpt"])

    def test_exact_call_keeps_singleton_without_extra_fetches(self):
        self.write("index.html", '<link rel="describedby" href="contracts.ndjson">')
        self.write("contracts.ndjson", """
{"href":"p/Stream.html#read(a.Value)","name":"read","returns":{"doc":"an A value."}}
{"href":"p/Stream.html#read(b.Value)","name":"read","returns":{"doc":"a B value."}}
""".strip())
        for question, anchors in [
            ("What does Stream.read(b.Value) return?", ["read(b.Value)"]),
            ("What does Stream.read(Value) return?", ["read(a.Value)", "read(b.Value)"]),
        ]:
            with self.subTest(question=question):
                result = route(self.entry, question, "scoped", alternatives=3)
                self.assertEqual([item["anchor"] for item in result["candidates"]], anchors)
                self.assertEqual(result["fetches"], 2)
                if len(anchors) == 1:
                    self.assertTrue(result["extraction"])
                else:
                    self.assertIsNone(result["extraction"])

    def test_ignores_links_to_external_documents(self):
        self.write("index.html", '<link rel="help" href="https://example.test/llms.txt">')
        with self.assertRaisesRegex(ValueError, "escaped local origin"):
            route(self.entry, "What does this library do?", "inline")

    def test_adaptive_uses_exact_markdown_anchor_after_sparse_contract(self):
        self.write("index.html", '<link rel="describedby" href="contracts.ndjson">'
                   '<link rel="alternate" type="text/markdown" href="index.md">')
        self.write("contracts.ndjson", """
{"href":"p/Value.html#read(a.Class)","name":"read","signature":"String read(Class type)","summary":"Read a value."}
{"href":"p/Value.html#read(b.Class)","name":"read","signature":"String read(Class type)","summary":"Read a value."}
""".strip())
        self.write("p/Value.md", """
# Value

### read(Class)
<a id="read(a.Class)"></a>

A result for type a.

### read(Class)
<a id="read(b.Class)"></a>

B result for type b.
""")
        result = route(self.entry, "What does Value.read(b.Class) return?", "adaptive")
        self.assertEqual(result["anchor"], "read(b.Class)")
        self.assertEqual(result["fetches"], 3)
        self.assertIn("B result", result["excerpt"])
        self.assertNotIn("A result", result["excerpt"])

    def test_scoped_finds_native_type_and_exact_overload_without_a_global_read(self):
        self.write("index.html", """
<link rel="alternate" type="text/markdown" href="index.md">
<link rel="describedby" href="contracts.ndjson">
<link rel="index" href="index-all.md">
<a class="nav-type" href="a/Value.html">Value</a>
<a class="nav-type" href="b/Value.html">Value</a>""")
        self.write("b/Value.md", """
### read(Class)
<a id="read(a.Class)"></a>

**Returns** String: the first result.

### read(Class)
<a id="read(b.Class)"></a>

**Returns** String: the second result.
""")
        result = route(self.entry, "What does b.Value.read(b.Class) return?", "scoped")
        self.assertEqual(result["path"], "b/Value")
        self.assertEqual(result["anchor"], "read(b.Class)")
        self.assertEqual([r["path"] for r in result["requests"]], ["index.html", "b/Value.md"])
        self.assertIn("the second result", result["excerpt"])
        self.assertNotIn("the first result", result["excerpt"])

    def test_budget_preserves_whole_return_and_skips_unrelated_long_prose(self):
        member = Member("Value", "read()", "read()", "")
        body = "Read a value. " + "word " * 100 + (
            " Returns: null if absent; otherwise the complete string. Throws: IOException - if read fails."
        )
        evidence = select_evidence("What does Value.read() return?", member, body, 70)
        self.assertIn("Returns: null if absent; otherwise the complete string.", evidence)
        self.assertNotIn("word ", evidence)
        self.assertLessEqual(len(evidence), 70)

    def test_unanchored_duplicate_display_title_abstains(self):
        member = Member("Value", "read(b.Class)", "read(b.Class)", "")
        ambiguous = "### read(Class)\n\nFirst value.\n\n### read(Class)\n\nSecond value.\n"
        self.assertEqual(markdown_detail(ambiguous, member, {"signature": "read(b.Class)"}), "")

    def test_markdown_does_not_treat_an_example_as_a_second_anchor(self):
        member = Member("Value", "read()", "read()", "")
        body = """### read()
<a id="read()"></a>

Returns the answer.

````markdown
### read()
<a id="read()"></a>
Misleading example.
````

### write()
<a id="write()"></a>
Writes a value.
"""
        detail = markdown_detail(body, member, {"signature": "read()"})
        self.assertIn("Returns the answer.", detail)
        self.assertNotIn("Writes a value", detail)
        self.assertNotIn("Misleading example", detail)


if __name__ == "__main__":
    unittest.main()
