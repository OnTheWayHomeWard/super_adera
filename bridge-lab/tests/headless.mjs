// Real headless browser run of the Bridge Lab page (Playwright, Chromium), against the running bridge-lab container.
//  1. no window.adera: the page must say "not developed" with what the super app must do, and the report must list it
//  2. a FAKE bridge (tests/stub-bridge.js, injected only here) that follows the spec: the checks must pass
//  3. broken fakes: an answer with a wrong id -> fail; a missing method -> not developed
// Run: bash bridge-lab/tests/run_headless.sh   (BASE defaults to http://localhost:8095)
import { chromium } from "playwright";
import fs from "node:fs";

const BASE = process.env.BASE || "http://localhost:8095";
const OUT = process.env.OUT || "/work/out";
const STUB = fs.readFileSync(new URL("./stub-bridge.js", import.meta.url), "utf8");
fs.mkdirSync(OUT, { recursive: true });

let failures = 0;
const results = [];
function check(name, cond, extra) {
  results.push({ name, ok: !!cond, extra });
  if (!cond) failures++;
  console.log((cond ? "PASS " : "FAIL ") + name + (extra ? "  " + JSON.stringify(extra).slice(0, 300) : ""));
}

async function newPage(browser, stubOpts, scheme) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme || "light" });
  if (stubOpts) await ctx.addInitScript(STUB + "\n;installAderaStub(window, " + JSON.stringify(stubOpts) + ");");
  const page = await ctx.newPage();
  page.on("pageerror", (e) => check("no page error", false, e.message));
  await page.goto(BASE + "/?entry=1");
  await page.waitForFunction(() => window.lab && document.querySelector("#who") && document.querySelector("#who").textContent.length > 0);
  return page;
}

async function runStep(page, testId, index, timeout = 20000) {
  await page.evaluate((id) => { location.hash = "#/t/" + id; }, testId);
  await page.waitForSelector(`#step-${index}`);
  await page.click(`#step-${index} [data-run]`);
  await page.waitForSelector(`#step-${index} .verdict`, { timeout });
  return page.evaluate(([id, i]) => window.lab.result(id).steps[i], [testId, index]);
}

const browser = await chromium.launch();
try {
  // ---------------------------------------------------------------------------------------- 1. no bridge
  {
    const page = await newPage(browser, null);
    check("header says no window.adera", (await page.textContent("#who")).includes("no window.adera"));
    check("header says no launch token", (await page.textContent("#who")).includes("no launch token"));
    check("104 tests listed on the home screen", (await page.$$("ul.tests li")).length === 104, (await page.$$("ul.tests li")).length);
    await page.screenshot({ path: OUT + "/1-home-light.png", fullPage: false });
    const b01 = await runStep(page, "B01", 0);
    check("B01 without a bridge -> notdev", b01.verdict === "notdev", b01);
    check("B01 says what the super app must do", (await page.textContent("#step-0 .must")).includes("Inject window.adera"));
    const p01 = await runStep(page, "P01", 0);
    check("P01 without a bridge -> notdev", p01.verdict === "notdev", p01.note);
    const l01 = await runStep(page, "L01", 0);
    check("L01 without a launch token -> notdev (launch)", l01.verdict === "notdev" && /launch/.test(l01.mustDo), l01.note);
    const e01 = await runStep(page, "E01", 0);
    check("E01 raw message without a channel -> notdev", e01.verdict === "notdev", e01.note);
    await page.evaluate(() => { location.hash = "#/t/B01"; });
    await page.waitForSelector("[data-mk=notdev]");
    check("B01 suggestion is 'Not developed yet'", (await page.textContent(".final")).includes("suggests: \u{1F6A7} Not developed yet"));
    await page.click("[data-mk=notdev]");
    await page.screenshot({ path: OUT + "/2-test-notdev.png", fullPage: true });
    await page.evaluate(() => { location.hash = "#/report"; });
    await page.waitForSelector("ul.todo");
    check("report lists B01 under what the super app must build", (await page.textContent("ul.todo")).includes("B01"));
    const md = await page.evaluate(() => window.lab.reportMarkdown());
    check("Markdown report has the to-build section with B01", md.includes("## What the super app still needs to build") && md.includes("B01"));
    await page.screenshot({ path: OUT + "/3-report.png", fullPage: true });
    // persistence: reload keeps the result
    await page.reload();
    await page.waitForFunction(() => window.lab);
    check("results survive a reload (localStorage)", await page.evaluate(() => window.lab.result("B01").status === "notdev"));
    // the backend log drawer receives entries (session note from marking)
    await page.click("#drawer-toggle");
    await page.click("[data-tab=backend]");
    await page.waitForFunction(() => document.querySelector("#kcount").textContent !== "0", null, { timeout: 10000 }).catch(() => {});
    check("backend log stream delivers entries", (await page.textContent("#kcount")) !== "0", await page.textContent("#kcount"));
    await page.close();
  }
  // ---------------------------------------------------------------------------------------- 2. a fake bridge that follows the spec
  {
    const page = await newPage(browser, {}, "dark");
    check("header says bridge injected", (await page.textContent("#who")).includes("bridge injected"));
    for (const [id, i, want] of [["B01", 0, "pass"], ["B02", 0, "pass"], ["B03", 0, "pass"], ["B04", 0, "pass"], ["B05", 0, "pass"], ["B06", 0, "pass"],
      ["P01", 0, "pass"], ["P09", 0, "pass"], ["P09", 1, "pass"], ["P12", 1, "pass"], ["E01", 0, "pass"], ["E01", 1, "pass"], ["E01", 2, "pass"], ["E01", 3, "pass"],
      ["E01", 4, "pass"], ["E01", 5, "pass"], ["E02", 0, "pass"], ["E03", 0, "pass"], ["E03", 1, "pass"], ["E03", 2, "pass"], ["E03", 3, "pass"],
      ["LO03", 1, "pass"], ["LO08", 0, "pass"], ["B09", 0, "pass"], ["E08", 0, "pass"], ["O01", 0, "pass"]]) {
      const v = await runStep(page, id, i);
      check(`stub: ${id} step ${i + 1} -> ${want}`, v.verdict === want, v.verdict !== want ? v : undefined);
    }
    const s01 = await runStep(page, "S01", 0);
    check("stub: openPermissionSettings shape validated", s01.verdict === "pass", s01);
    const b10 = await runStep(page, "B10", 0, 60000);
    check("stub: getPermission for all 10 capabilities", b10.verdict === "pass", b10);
    await page.click("#drawer-toggle");
    await page.waitForSelector("#log-list .row");
    const rows = await page.$$eval("#log-list .row", (r) => r.length);
    check("bridge log shows messages in both directions", rows > 20 && (await page.$("#log-list .d-out")) && (await page.$("#log-list .d-in")), rows);
    await page.screenshot({ path: OUT + "/4-stub-dark-log.png", fullPage: false });
    await page.close();
  }
  // ---------------------------------------------------------------------------------------- 3. broken fakes
  {
    const page = await newPage(browser, { broken: "wrong-id" });
    const v = await runStep(page, "P01", 0, 25000);
    check("wrong-id answers -> fail (id mismatch), not 'not developed'", v.verdict === "fail" && /id/.test(v.note), v.note);
    const b09 = await runStep(page, "B09", 0);
    check("wrong-id answers are listed as unsolicited (B09 fail)", b09.verdict === "fail", b09.note);
    await page.close();
  }
  {
    const page = await newPage(browser, { broken: "no-getLocation" });
    const v = await runStep(page, "LO08", 0);
    check("missing getLocation -> notdev naming the method", v.verdict === "notdev" && /getLocation/.test(v.note), v.note);
    await page.close();
  }
  {
    const page = await newPage(browser, { broken: "silent" });
    const v = await runStep(page, "P09", 1, 25000);
    check("no answer in 10 s -> notdev 'did not answer'", v.verdict === "notdev" && /did not answer/.test(v.note), v.note);
    await page.close();
  }
} finally {
  await browser.close();
}
fs.writeFileSync(OUT + "/headless-results.json", JSON.stringify(results, null, 2));
console.log(`\n${results.length - failures}/${results.length} checks passed`);
process.exit(failures ? 1 : 0);
