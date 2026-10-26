// Browser walk-through (Playwright). Prints one line per step; screenshots go to /work/out.
import { chromium } from "playwright";
import { createHmac } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const GATEWAY = process.env.GATEWAY_URL ?? "http://127.0.0.1:9080";
const EMAIL = process.env.STAFF_EMAIL;
const BASE = `${GATEWAY}/backoffice`;
const OUT = "/work/out";
const stateName = ".staff-totp-" + EMAIL.toLowerCase().replace(/[^a-z0-9]/g, "_") + ".json";
const stateFile = existsSync("/e2e/" + stateName) ? "/e2e/" + stateName : "/work/out/" + stateName;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function b32(text) {
  const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of text.toUpperCase().replace(/=+$/, "")) bits += a.indexOf(c).toString(2).padStart(5, "0");
  const out = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) out.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(out);
}
function totp(secret) {
  const c = Buffer.alloc(8);
  c.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const d = createHmac("sha1", b32(secret)).update(c).digest();
  const o = d[d.length - 1] & 15;
  return String((d.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, "0");
}
let lastWindow = -1;
async function freshTotp(secret) {
  while (Math.floor(Date.now() / 30000) <= lastWindow) await sleep(1000);
  lastWindow = Math.floor(Date.now() / 30000);
  return totp(secret);
}
async function emailedCode() {
  for (let i = 0; i < 20; i++) {
    const r = await fetch(`http://127.0.0.1:8085/dev/messages?to=${encodeURIComponent(EMAIL)}`);
    const list = await r.json().catch(() => []);
    const hit = list[0] && /\b(\d{6})\b/.exec(`${list[0].subject ?? ""} ${list[0].body ?? ""}`);
    if (hit) return hit[1];
    await sleep(1000);
  }
  throw new Error("no emailed code");
}

// Looks at every screen that shows the data made by tests/e2e/seed_scenarios.py: no error panels, the expected rows, and (for a
// VIEWER) masked contact details. Screenshots go to /work/out/seed-*.png. Run: SCRIPT=seed-screens.mjs backoffice/tests/ui/run.sh
const results = [];
const state = JSON.parse(readFileSync("/e2e/.seed-scenarios-state.json", "utf8"));
const ROLE = process.env.ROLE ?? "SUPER_ADMIN";
let shot = 0;
const consoleErrors = [];
async function step(page, name, fn) {
  try {
    await fn();
    await page.screenshot({ path: `${OUT}/seed-${ROLE.toLowerCase()}-${String(++shot).padStart(2, "0")}-${name.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}.png`, fullPage: true });
    results.push({ name, ok: true });
    console.log(`  ok  ${name}`);
  } catch (e) {
    await page.screenshot({ path: `${OUT}/seed-FAIL-${name.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}.png`, fullPage: true }).catch(() => undefined);
    results.push({ name, ok: false, error: String(e).split("\n")[0] });
    console.log(`FAIL  ${name}: ${String(e).split("\n")[0]}`);
  }
}
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on("console", (m) => m.type() === "error" && consoleErrors.push(m.text()));
page.on("pageerror", (e) => consoleErrors.push(String(e)));
const noErrors = async () => { await sleep(900); if ((await page.locator(".ant-alert-error").count()) > 0) throw new Error("an error panel is showing: " + (await page.locator(".ant-alert-error").first().innerText()).slice(0, 120)); };
const text = async () => await page.locator("body").innerText();
const expectText = async (...needles) => { const t = await text(); for (const n of needles) if (!t.includes(n)) throw new Error(`missing text: ${n}`); };

await page.goto(`${BASE}/`);
await page.waitForURL(/\/backoffice\/login/);
await fetch("http://127.0.0.1:8085/dev/messages", { method: "DELETE" });
await page.getByLabel("Work email").fill(EMAIL);
await page.getByRole("button", { name: "Send me a code" }).click();
await page.getByText("We sent a 6-digit code").waitFor();
await page.getByLabel("6-digit code").fill(await emailedCode());
await page.getByRole("button", { name: "Continue" }).click();
await page.getByText("Enter the 6-digit code from your authenticator app").waitFor();
await page.getByLabel("Authenticator code").fill(await freshTotp(JSON.parse(readFileSync(stateFile, "utf8")).secret));
await page.getByRole("button", { name: "Sign in" }).click();
await page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: 20000 });

await step(page, "dashboard", async () => { await noErrors(); await expectText("Mini apps by status"); });
await step(page, "mini apps list shows all apps with ratings", async () => {
  await page.goto(`${BASE}/apps`); await page.getByRole("heading", { name: "Mini apps" }).waitFor(); await noErrors();
  await expectText("demo-food", "demo-quota", "demo-killed", "demo-paused", "KILLED", "PAUSED", "DRAFT", "3.3");
});
const tabs = ["General", "Capabilities", "Notification limits", "Environments and IPs", "Partner keys", "Payment", "Templates", "Notification log", "Usage", "Reviews", "Reports", "Audit"];
for (const slug of ["demo-food", "demo-quota", "demo-killed", "demo-paused"]) {
  await step(page, `app ${slug}: every tab renders`, async () => {
    await page.goto(`${BASE}/apps/${state.apps[slug].id}`);
    for (const t of tabs) {
      await page.getByRole("tab", { name: t }).click();
      await noErrors();
      if (t === "Partner keys" && slug === "demo-food") await expectText("ak_dev_", "REVOKED");
      if (t === "Templates" && slug === "demo-food") await expectText("seed-order-confirmed", "seed-weekend-promo");
      if (t === "Notification log" && slug === "demo-food") await expectText("SUPPRESSED", "UNREACHABLE");
      if (t === "Usage" && slug === "demo-quota") await expectText("EMAIL");
      if (t === "Reviews" && slug === "demo-food") await expectText("HIDDEN");
      if (t === "Reports" && slug === "demo-food") await expectText("SPAM");
    }
  });
}
await step(page, "moderation: reports and reviews", async () => {
  await page.goto(`${BASE}/moderation`); await noErrors(); await expectText("demo-food");
  await page.goto(`${BASE}/moderation?tab=reviews`); await noErrors(); await expectText("demo-food");
});
await step(page, "users list", async () => {
  await page.goto(`${BASE}/users`); await noErrors(); await expectText("Demo");
  if (ROLE === "VIEWER") { const t = await text(); if (/\+251900000001/.test(t)) throw new Error("full phone visible to a viewer"); }
});
for (const k of ["u01", "u04", "u11", "u12", "u14"]) {
  await step(page, `user ${k}`, async () => {
    await page.goto(`${BASE}/users/${state.users[k].id}`); await noErrors(); await expectText("Mute preferences per app");
    if (k === "u11") await expectText("SUSPENDED");
    if (k === "u12") await expectText("DELETED");
    if (ROLE === "VIEWER" && k === "u01") { const t = await text(); if (t.includes(state.users[k].phone) || t.includes("demo.abebe@example.com")) throw new Error("contact details visible to a viewer"); }
    if (ROLE !== "VIEWER" && k === "u01") await expectText(state.users[k].phone, "minibus driver");
  });
}
await step(page, "notifications list and a detail", async () => {
  await page.goto(`${BASE}/notifications`); await noErrors(); await expectText("SENT");
  await page.locator("a[href*='/notifications/']").first().click(); await noErrors();
});
if (ROLE === "SUPER_ADMIN") {
  await step(page, "staff", async () => { await page.goto(`${BASE}/staff`); await noErrors(); await expectText("demo-viewer-1@adera.local", "DISABLED"); });
}
if (ROLE !== "VIEWER") {
  await step(page, "audit log", async () => { await page.goto(`${BASE}/audit`); await noErrors(); await expectText("app.status", "user.status"); });
}
await step(page, "system", async () => { await page.goto(`${BASE}/system`); await noErrors(); });
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} steps passed (${ROLE})`);
if (consoleErrors.length) console.log("browser console errors:\n  " + [...new Set(consoleErrors)].slice(0, 10).join("\n  "));
process.exit(failed.length ? 1 : 0);
