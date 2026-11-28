// Validate each module independently so one successful scan cannot mask a skipped module.
import assert from "node:assert/strict";
import { parse } from "node-html-parser";
import { execFile } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const [base, mode = "candidate"] = process.argv.slice(2);
assert.ok(["candidate", "deployed"].includes(mode));
await mkdir(".dev/quality", { recursive: true });
for (const [module, page] of [
  ["api/java", "example/runtime/BuildPlan.html"],
  ["api/kotlin", "example/Greeter.html"],
  ["guides/handbook", "guides/runtime-greetings.html"],
]) {
  const root = new URL(module + "/", base);
  const index = new URL("llms.txt", root);
  const target = new URL(page, root);
  const html = await fetch(target);
  assert.equal(html.status, 200);
  const head = parse(await html.text()).querySelector("head");
  assert.ok(head, target.href);
  const markdown = head.querySelectorAll('link[rel="alternate"][type="text/markdown"]');
  assert.equal(markdown.length, 1, target.href);
  const twin = new URL(markdown[0].getAttribute("href"), target);
  assert.equal(twin.href, target.href.replace(/\.html$/, ".md"));
  const direct = await fetch(twin);
  assert.equal(direct.status, 200);
  assert.match(direct.headers.get("content-type"), /^text\/markdown\b/i);
  await direct.body.cancel();
  const guide = module.startsWith("guides/");
  const targets = [
    ['link[rel="help"][type="text/plain"]', "llms.txt"],
    ['link[rel="describedby"][type="text/plain"]', "llms-full.txt"],
    ['link[rel="index"]', guide ? "index.html" : "index-all.md"],
  ];
  const contract = 'link[rel="describedby"][type="application/x-ndjson"]';
  if (guide) assert.equal(head.querySelector(contract), null, target.href);
  else targets.push([contract, "contracts.ndjson"]);
  for (const [selector, filename] of targets) {
    const href = head.querySelector(selector)?.getAttribute("href");
    assert.ok(href, `${target.href} is missing ${filename}`);
    const discovered = new URL(href, target);
    assert.equal(discovered.href, new URL(filename, root).href);
    const entry = await fetch(discovered);
    assert.equal(entry.status, 200, discovered.href);
    await entry.body.cancel();
  }
  const checks = ["llms-txt-exists", "llms-txt-valid", "markdown-url-support", "markdown-content-parity"];
  if (mode === "candidate") checks.push("content-negotiation");
  const cli = fileURLToPath(new URL("./node_modules/.bin/afdocs", import.meta.url));
  const args = ["check", root.href, "--llms-txt-url", index.href, "--urls", target.href,
    "--url-path-pattern", "html", "--checks", checks.join(","), "--quiet", "--format", "json"];
  let raw;
  try {
    ({ stdout: raw } = await promisify(execFile)(cli, args, { timeout: 90000 }));
  } catch (error) {
    if (!error.stdout) throw error;
    raw = error.stdout;
  }
  await writeFile(`.dev/quality/afdocs-${mode}-${module.replace("/", "-")}.json`, raw);
  const report = JSON.parse(raw);
  for (const id of checks) {
    const result = report.results.find((item) => item.id === id);
    assert.equal(result?.status, "pass", module + " " + id + ": " + result?.message);
    if (result.details?.pageResults) {
      assert.ok(result.details.pageResults.some((item) => item.url === target.href));
    }
  }
  // AFDocs skips relative index links. Resolve every published entry as a reader would.
  const response = await fetch(index);
  assert.equal(response.status, 200);
  const links = [...(await response.text()).matchAll(/^\s*- \[[^\]]+\]\(([^)]+)\)/gm)]
    .map((match) => new URL(match[1], index));
  assert.ok(links.some((link) => link.href === target.href.replace(/\.html$/, ".md")));
  for (const link of links) {
    assert.ok(link.href.startsWith(root.href), "Index escapes module: " + link.href);
    const entry = await fetch(link);
    assert.equal(entry.status, 200, link.href);
    const body = await entry.text();
    assert.ok(body.trim() && !/^\s*(?:<!doctype html|<html)/i.test(body), link.href);
  }
  if (mode === "deployed") {
    const negotiated = await fetch(target, { headers: { Accept: "text/markdown" } });
    assert.equal(negotiated.status, 200);
    console.log("Deployed Accept: text/markdown", target.href, negotiated.headers.get("content-type"));
  }
  console.log(module, checks.length, "AFDocs checks passed;", links.length, "index entries resolved");
}
