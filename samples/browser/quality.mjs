// Release gates over the built catalog, served through its public HTTP entry point.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import stylelint from "stylelint";
import { chromium } from "playwright-core";
import lighthouse from "lighthouse";
import { launch } from "chrome-launcher";

const root = path.resolve(".dev/pages");
const reports = path.resolve(".dev/quality");
fs.mkdirSync(reports, { recursive: true });
const local = (file) => fileURLToPath(new URL(file, import.meta.url));
const configFile = local("./good-css.json");
async function css(code, codeFilename) {
  const result = await stylelint.lint({ code, codeFilename, configFile });
  assert.ok(!result.errored, result.report);
}
await css(fs.readFileSync(root + "/gallery.css", "utf8"), "gallery.css");
for (const theme of fs.readdirSync(root + "/themes")) {
  const rules = JSON.parse(fs.readFileSync(root + "/themes/" + theme)).css;
  if (rules) await css(rules, theme + ".css");
}
for (const file of ["preview.html", "catalog/latest/index.html"]) {
  for (const match of fs
    .readFileSync(path.join(root, file), "utf8")
    .matchAll(/<style>([\s\S]*?)<\/style>/g))
    await css(match[1], file + ".css");
}

const server = spawn(process.execPath, [
  "doclet/src/main/resources/dev/elide/apidocs/catalog/catalog.mjs",
  "serve",
  root,
  "0",
]);
let browser, chrome;
try {
  const [line] = await once(server.stdout, "data");
  const origin = line.toString().trim().split(" ").at(-1);
  const base = origin + "/catalog/latest/";
  await promisify(execFile)(process.execPath, [local("./agent-gate.mjs"), base]);
  const guide = "guides/handbook/guides/runtime-greetings.html";
  const java = "api/java/example/runtime/BuildPlan.html";
  const kotlin = "api/kotlin/example/Greeter.html";
  browser = await chromium.launch({ channel: "chrome" });
  const page = await browser.newPage();
  for (const resource of [guide, java, kotlin]) {
    await page.goto(base + resource);
    const memberAnchors = [];
    if (resource !== guide) {
      const module = new URL(resource.split("/").slice(0, 2).join("/") + "/", base);
      const response = await fetch(new URL("contracts.ndjson", module));
      assert.equal(response.status, 200);
      assert.match(response.headers.get("content-type"), /ndjson/);
      const records = (await response.text()).trim().split("\n").map(JSON.parse);
      const lookups = resource === java
        ? [
            ["example.runtime.BuildPlan.defaults(String)", "example/runtime/BuildPlan.html#defaults(java.lang.String)"],
            ["example.runtime.Planner.Options.Detail", "example/runtime/Planner.Options.Detail.html"],
          ]
        : [
            ["example.Greeter(String)", "example/Greeter.html#<init>(kotlin.String)"],
            ["example.Greeter.greet()", "example/Greeter.html#greet()"],
            ["example.Greeter.name", "example/Greeter.html#name"],
          ];
      for (const [symbol, target] of lookups) {
        assert.ok(records.some(record => record.symbol === symbol &&
          decodeURIComponent(record.href) === target), symbol);
      }
      assert.match(await page.locator('link[type="application/x-ndjson"]').getAttribute("title"), /Type\.member/);
      assert.match(await (await fetch(new URL("llms.txt", module))).text(), /Type\.member/);
      const anchors = await page.locator("[id]").evaluateAll(nodes => nodes.map(node => node.id));
      const members = records.filter(record => new URL(record.href, module).pathname === new URL(page.url()).pathname);
      assert.ok(members.some(record => !new URL(record.href, module).hash));
      assert.ok(members.some(record => new URL(record.href, module).hash));
      for (const member of members) {
        assert.ok(member.name && member.signature && member.kind);
        const fragment = new URL(member.href, module).hash;
        if (fragment) {
          const anchor = decodeURIComponent(fragment.slice(1));
          assert.ok(anchors.includes(anchor), member.href);
          memberAnchors.push(anchor);
        }
      }
    }
    const twin = await page
      .locator('link[rel=alternate][type="text/markdown"]')
      .first()
      .getAttribute("href");
    const response = await fetch(base + resource, {
      headers: { Accept: "text/markdown" },
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type"), /text\/markdown/);
    assert.match(response.headers.get("vary"), /Accept/i);
    const direct = await fetch(new URL(twin, page.url()));
    const markdown = await direct.text();
    assert.equal(markdown, await response.text());
    if (memberAnchors.length) {
      const markdownAnchors = await page.evaluate(source =>
        [...source.matchAll(/^### [^\n]+\n(<a id="[^"\n]+"><\/a>)$/gm)].map(match =>
          new DOMParser().parseFromString(match[1], "text/html").querySelector("a").id),
      markdown);
      assert.deepEqual(markdownAnchors.sort(), memberAnchors.sort(), resource);
      const glance = markdown.split("## API at a glance\n\n")[1].split("\n\n")[0];
      const linked = [...glance.matchAll(/^- \[.+?\]\(#([^\s)]+)\):/gm)]
        .map(match => decodeURIComponent(match[1]));
      const expected = resource === java
        ? ["<init>(java.lang.String,java.util.List)", "defaults(java.lang.String)"]
        : ["greet()"];
      for (const anchor of expected) assert.ok(linked.includes(anchor), anchor);
      for (const anchor of linked) assert.ok(markdownAnchors.includes(anchor), anchor);
    }
    const jsonld = await page
      .locator('script[type="application/ld+json"]')
      .first()
      .textContent();
    assert.ok(JSON.parse(jsonld)["@context"]);
    const target =
      resource === guide
        ? "BuildPlan.defaults"
        : "Prepare a Kotlin greeting for a Java runtime";
    const dest = await page
      .locator("main")
      .getByRole("link", { name: target, exact: true })
      .first()
      .getAttribute("href");
    assert.equal(
      new URL(dest, page.url()).pathname,
      new URL(base + (resource === guide ? java : guide)).pathname,
    );
  }
  await page.goto(base + guide);
  await page
    .locator("main")
    .getByRole("link", { name: "BuildPlan.defaults", exact: true })
    .click();
  assert.ok(await page.locator('[id="defaults(java.lang.String)"]').count());
  await page.locator("[data-search] input").fill("Greeter");
  await page
    .locator("[data-search] a")
    .filter({ hasText: "Greeter" })
    .first()
    .click();
  assert.ok(page.url().includes("/api/kotlin/"), page.url());
  const handbook = base + "guides/handbook/";
  assert.match(
    await (await fetch(handbook + "llms.txt")).text(),
    /runtime-greetings\.md/,
  );
  assert.equal(
    (await fetch(handbook + "guides/drafts/preview.html")).status,
    404,
  );
  const model = await (await fetch(handbook + "api.json")).json();
  assert.equal(model.schema, "apidocs/1");
  assert.ok(
    (await (await fetch(handbook + "llms-full.txt")).text()).includes(
      "Choose an entry point",
    ),
  );
  await browser.close();
  browser = null;
  chrome = await launch({ chromeFlags: ["--headless", "--no-sandbox"] });
  for (const resource of [guide, java, kotlin]) {
    const { lhr } = await lighthouse(base + resource, {
      port: chrome.port,
      logLevel: "error",
      onlyCategories: ["performance", "accessibility", "best-practices", "seo"],
    });
    fs.writeFileSync(
      path.join(reports, path.basename(resource) + ".json"),
      JSON.stringify(lhr),
    );
    for (const [category, min] of Object.entries({
      performance: 0.9,
      accessibility: 1,
      "best-practices": 0.95,
      seo: 0.95,
    })) {
      assert.ok(
        lhr.categories[category].score >= min,
        resource + " " + category + " " + lhr.categories[category].score,
      );
    }
    assert.ok(
      lhr.audits["cumulative-layout-shift"].numericValue <= 0.05,
      "layout shift: " + resource,
    );
    console.log(
      resource,
      Object.fromEntries(
        Object.entries(lhr.categories).map(([key, c]) => [key, c.score]),
      ),
    );
  }
} finally {
  await browser?.close();
  await chrome?.kill();
  server.kill();
}
