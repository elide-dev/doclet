// Exercise the shipping catalog with distinct versions and modules of a generated site.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync, spawn } from "node:child_process";
import { once } from "node:events";
import { chromium } from "../samples/browser/node_modules/playwright-core/index.mjs";

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "reference-catalog-"));
const cli = path.resolve(
  "doclet/src/main/resources/dev/elide/apidocs/catalog/catalog.mjs",
);
const source = path.resolve(".dev/pages/graalvm");
let server, browser;
try {
  const previous = path.join(dir, "previous");
  fs.cpSync(source, previous, { recursive: true });
  const modelPath = path.join(previous, "api.json");
  const model = JSON.parse(fs.readFileSync(modelPath));
  const type = model.packages
    .flatMap((p) => p.types)
    .find((t) => t.id === "example.runtime.BuildPlan");
  type.members = type.members.filter((m) => m.name !== "defaults");
  fs.writeFileSync(modelPath, JSON.stringify(model));
  const manifest = path.join(dir, "catalog.json"),
    out = path.join(dir, "out");
  const suite = path.join(dir, "handbook");
  fs.mkdirSync(path.join(suite, "drafts"), { recursive: true });
  fs.writeFileSync(
    path.join(suite, "drafts", "preview.md"),
    "# Draft guide\n\nRead [the plan](api/core/example.runtime.BuildPlan).\n",
  );
  fs.writeFileSync(
    manifest,
    JSON.stringify({
      latest: "v2",
      versions: [
        {
          id: "v2",
          includeDrafts: true,
          suites: { handbook: { directory: suite } },
          modules: { core: source, tools: source },
        },
        { id: "v1", modules: { core: previous } },
      ],
    }),
  );
  const jar = path.resolve(
    "doclet/.dev/artifacts/jar/elide-doclet/elide-doclet.jar",
  );
  const build = (dest) =>
    execFileSync(
      "java",
      ["-cp", jar, "dev.elide.apidocs.render.Catalog", "build", manifest, dest],
      { stdio: "pipe" },
    );
  build(out);
  assert.ok(
    fs.existsSync(
      path.join(out, "v2/guides/handbook/guides/drafts/preview.html"),
    ),
  );
  fs.writeFileSync(
    path.join(suite, "broken.md"),
    "# Broken\n\n[missing](api/core/example.Missing)\n",
  );
  assert.throws(
    () => build(path.join(dir, "invalid")),
    /Unknown cross-module API link/,
  );
  assert.ok(!fs.existsSync(path.join(dir, "invalid")));
  assert.match(
    fs.readFileSync(path.join(out, "v2/changes/v1.html"), "utf8"),
    /Added core \/ example.runtime.BuildPlan#defaults/,
  );
  assert.match(
    fs.readFileSync(path.join(out, "latest/index.html"), "utf8"),
    /Changes since v1/,
  );
  server = spawn(process.execPath, [cli, "serve", out, "0"]);
  const [line] = await once(server.stdout, "data");
  const origin = line.toString().trim().split(" ").at(-1);
  const typeURL = origin + "/latest/api/core/example/runtime/BuildPlan.html";
  const response = await fetch(typeURL, {
    headers: { Accept: "text/markdown" },
  });
  assert.equal(response.headers.get("Vary"), "Accept");
  assert.equal(response.headers.get("Content-Type"), "text/markdown");
  assert.match(await response.text(), /BuildPlan/);
  browser = await chromium.launch({ channel: "chrome" });
  const page = await browser.newPage();
  await page.goto(origin + "/latest/index.html");
  await page.locator("#query").fill("Prepare your first build");
  const matches = page.locator("#results a");
  await matches
    .filter({ hasText: "tools · Prepare your first build" })
    .first()
    .waitFor();
  await page.goto(typeURL);
  await page.locator(".catalog-menu summary").click();
  await page.locator(".catalog-menu").getByText("v1", { exact: true }).click();
  assert.equal(
    new URL(page.url()).pathname,
    "/v1/api/core/example/runtime/BuildPlan.html",
  );
} finally {
  if (browser) await browser.close();
  server?.kill();
  fs.rmSync(dir, { recursive: true, force: true });
}
