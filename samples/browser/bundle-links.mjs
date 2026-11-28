import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fromMarkdown } from "mdast-util-from-markdown";
import { parse } from "node-html-parser";

function content(markdown, resolve) {
  const tree = fromMarkdown(markdown);
  function visit(node) {
    delete node.position;
    if (node.type === "link" || node.type === "image") node.url = resolve(node.url);
    node.children?.forEach(visit);
  }
  visit(tree);
  return tree;
}

// Compare complete CommonMark trees so code, raw anchors and prose remain literal.
export function checkBundleLinks(catalog) {
  const bundle = new URL("../../doclet/src/main/resources/dev/elide/apidocs/renderer/render.js", import.meta.url);
  const render = vm.runInNewContext(fs.readFileSync(bundle, "utf8") + "\nApiDocs.render", { URL });
  const mount = new URL("https://example.test/published/catalog/latest/");
  const anchors = new Map();
  for (const module of ["api/java", "api/kotlin", "guides/handbook"]) {
    const base = new URL(module + "/", mount);
    const model = fs.readFileSync(path.join(catalog, module, "api.json"), "utf8");
    const twins = render(model);
    for (const markdown of [true, false]) {
      const site = markdown ? twins : render(model, {}, { markdown: false });
      const parts = site["llms-full.txt"].split(/^<!-- (\S+\.(?:md|html)) -->\n/gm);
      assert.ok(parts.length >= 3 && parts[0] === "", module);
      for (let i = 1; i < parts.length; i += 2) {
        const source = parts[i];
        assert.ok(Object.hasOwn(site, source), source);
        const twin = source.replace(/\.html$/, ".md");
        const body = twins[twin].replace(/^---\n[\s\S]*?\n---\n/, "");
        const expected = content(body, href => {
          const url = new URL(href, new URL(twin, base));
          const file = decodeURIComponent(url.pathname.slice(base.pathname.length));
          if (!markdown && url.href.startsWith(base.href) && file.endsWith(".md") && Object.hasOwn(twins, file)) {
            url.pathname = url.pathname.slice(0, -3) + ".html";
          }
          return url.href;
        });
        const actual = content(parts[i + 1], href => {
          const url = new URL(href, new URL("llms-full.txt", base));
          if (url.href.startsWith(mount.href)) {
            const file = path.join(catalog, decodeURIComponent(url.pathname.slice(mount.pathname.length)));
            assert.ok(fs.existsSync(file), `${module} / ${source} links to ${href}`);
            if (url.hash && /\.(?:md|html)$/.test(file)) {
              const html = file.replace(/\.md$/, ".html");
              if (!anchors.has(html)) anchors.set(html, new Set(parse(fs.readFileSync(html, "utf8"))
                .querySelectorAll("[id]").map(element => element.getAttribute("id"))));
              assert.ok(anchors.get(html).has(decodeURIComponent(url.hash.slice(1))), href);
            }
          }
          return url.href;
        });
        assert.deepEqual(actual, expected, `${module} / ${source}`);
      }
    }
  }
}
