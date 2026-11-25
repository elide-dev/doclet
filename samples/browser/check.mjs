import assert from "node:assert/strict";
import { chromium } from "playwright-core";

const browser = await chromium.launch({ channel: "chrome" });
try {
  const page = await browser.newPage();
  page.setDefaultTimeout(5000);
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(new URL("preview.html", process.argv[2]).href);
  const docs = page.frameLocator("#reference");
  for (const theme of await page.locator("#theme option").evaluateAll(options => options.map(o => o.value))) {
    await page.selectOption("#theme", theme);
    await page.waitForFunction(theme => document.querySelector("iframe").contentDocument?.URL.includes("/" + theme + "/"), theme);
    const target = docs.locator('main a[href="Target.html"]').first();
    await target.hover();
    const card = docs.locator("#apidocs-card:popover-open");
    await card.waitFor();
    assert.match(await card.innerText(), /Selects the runtime/);
    await target.press("Escape");
    await card.waitFor({ state: "hidden" });
  }
  const search = docs.locator("[data-search] input");
  await search.fill("BuildStep");
  await docs.locator("#apidocs-result-0").waitFor();
  await search.press("Enter");
  await docs.locator("h1").filter({ hasText: "BuildStep" }).waitFor();
  const filter = docs.locator("[data-nav-filter] input");
  await filter.fill("Planner");
  await docs.locator("[data-filtered-out]").first().waitFor({ state: "attached" });
  await filter.fill("");
  await docs.locator("[data-theme-switch]").click();
  await docs.locator('html[data-theme="light"]').waitFor();
  await docs.locator("[data-keys-open]").click();
  await docs.locator("dialog[open]").waitFor();
  await page.keyboard.press("Escape");
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
}
