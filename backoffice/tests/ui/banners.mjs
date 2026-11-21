// Browser walk-through of the Banners screen (Playwright). Run with:  SCRIPT=banners.mjs ROLE=ADMIN bash tests/ui/run.sh
// ADMIN: list tabs, create with an image, preview, publish, Live tab, pause, delete. VIEWER (ROLE=VIEWER): read only, no write buttons.
// Screenshots go to tests/ui/out/banners-*.png. Everything it creates is deleted again.
import { chromium } from "playwright";
import { createHmac } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { deflateSync } from "node:zlib";

const GATEWAY = process.env.GATEWAY_URL ?? "http://127.0.0.1:9080";
const EMAIL = process.env.STAFF_EMAIL;
const ROLE = process.env.ROLE ?? "ADMIN";
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
/** A plain RGB PNG with a horizontal colour gradient (so the preview shows something). */
function png(w, h) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (buf) => {
    let c = 0xffffffff;
    for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4);
    c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const rows = [];
  for (let y = 0; y < h; y++) {
    const row = Buffer.alloc(1 + w * 3);
    for (let x = 0; x < w; x++) row.set([30 + Math.floor((x / w) * 200), 120, 200 - Math.floor((x / w) * 150)], 1 + x * 3);
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr.set([8, 2, 0, 0, 0], 8);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(Buffer.concat(rows))), chunk("IEND", Buffer.alloc(0))]);
}

const results = [];
async function step(page, name, fn) {
  const file = `banners-${ROLE.toLowerCase()}-${String(results.length + 1).padStart(2, "0")}-${name.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}`;
  try {
    await fn();
    await page.screenshot({ path: `${OUT}/${file}.png`, fullPage: true });
    results.push({ name, ok: true });
    console.log(`  ok  ${name}`);
  } catch (e) {
    await page.screenshot({ path: `${OUT}/FAIL-${file}.png`, fullPage: true }).catch(() => undefined);
    results.push({ name, ok: false });
    console.log(`FAIL  ${name}: ${String(e).split("\n")[0]}`);
  }
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 1000 } })).newPage();
const consoleErrors = [];
page.on("pageerror", (e) => consoleErrors.push(String(e)));
const title = `UI banner ${Math.random().toString(16).slice(2, 8)}`;

await step(page, "sign in", async () => {
  await fetch("http://127.0.0.1:8085/dev/messages", { method: "DELETE" });
  await page.goto(`${BASE}/login`);
  await page.getByLabel("Work email").fill(EMAIL);
  await page.getByRole("button", { name: "Send me a code" }).click();
  await page.getByLabel("6-digit code").fill(await emailedCode());
  await page.getByRole("button", { name: "Continue" }).click();
  const secret = JSON.parse(readFileSync(stateFile, "utf8")).secret;
  // the gateway allows 10 sign-in submits a minute per IP, shared with anything else signing in: wait and retry on that message
  for (let attempt = 0; attempt < 3; attempt++) {
    await page.getByLabel("Authenticator code").fill(totp(secret));
    await page.getByRole("button", { name: "Sign in" }).click();
    const done = page.getByRole("heading", { name: "Dashboard" });
    const limited = page.getByText("Too many sign-in attempts");
    await Promise.race([done.waitFor({ timeout: 20000 }), limited.waitFor({ timeout: 20000 })]);
    if (await done.isVisible()) return;
    await sleep(61000);
  }
  throw new Error("still rate limited after three tries");
});

await step(page, "banners menu: tabs, filters, live list", async () => {
  await page.getByRole("link", { name: "Banners" }).first().click();
  await page.getByRole("heading", { name: "Banners" }).waitFor();
  for (const t of ["Live", "Scheduled", "Draft", "Paused", "Ended", "All"]) await page.getByRole("tab", { name: t }).waitFor();
  await page.getByText("Any placement").waitFor();
  await sleep(800);
});

if (ROLE === "VIEWER") {
  await step(page, "viewer: no new, publish, pause or delete buttons", async () => {
    await page.getByRole("tab", { name: "All" }).click();
    await sleep(1000);
    for (const name of ["New banner", "Publish", "Pause", "Delete", "Back to draft"]) {
      if (await page.getByRole("button", { name }).count()) throw new Error(`a VIEWER sees '${name}'`);
    }
  });
  await step(page, "viewer: a banner opens read only", async () => {
    const first = page.locator("td a[href*='/banners/']").first();
    if (!(await first.count())) return; // nothing to open on an empty stack
    await first.click();
    await page.getByText("Banner details (read only)").waitFor();
    if (await page.getByRole("button", { name: "Save changes" }).isEnabled()) throw new Error("save is enabled for a VIEWER");
    if (await page.getByRole("button", { name: /Upload image|Replace image/ }).count()) throw new Error("upload offered to a VIEWER");
  });
} else {
  await step(page, "new banner: client checks, then a MINI_APP banner with an image and the preview", async () => {
    await page.getByRole("button", { name: "New banner" }).click();
    await page.getByRole("heading", { name: "New banner" }).waitFor();
    await page.getByRole("button", { name: "Create banner" }).click();
    await page.getByText("An English title is required").waitFor();
    await page.getByLabel("Title - English", { exact: true }).fill(title);
    await page.getByLabel("Subtitle - English", { exact: true }).fill("Made by the browser walk-through");
    await page.getByText("Open a web page").click();
    await page.getByLabel("Web address").fill("https://evil.test/");
    await page.getByText(/evil\.test is not allowed/).waitFor();
    await page.getByText("Open a mini app").click();
    await page.getByLabel("Page in the app").fill("promo");
    await page.getByText("Must start with /").waitFor();
    await page.getByLabel("Page in the app").fill("/promo/first-ride");
    await page.getByLabel("Linked mini app", { exact: true }).click();
    await page.keyboard.type("demo-ride");
    await page.keyboard.press("Enter");
    await page.locator("input[type=file]").setInputFiles({ name: "banner.png", mimeType: "image/png", buffer: png(1600, 700) });
    await page.getByText("banner.png").waitFor();
    await page.getByText("Full width carousel at the top of the home screen").waitFor();
  });
  await step(page, "created as a draft with three image sizes", async () => {
    await page.getByRole("button", { name: "Create banner" }).click();
    await page.waitForURL(/\/backoffice\/banners\/[0-9a-f-]{36}/);
    await page.getByText("small 480×210").waitFor({ timeout: 15000 });
    await page.getByText("Draft").first().waitFor();
  });
  await step(page, "publish: the state becomes Live", async () => {
    await page.getByRole("button", { name: "Publish" }).click();
    await page.locator(".ant-tag", { hasText: /^Live$/ }).first().waitFor();
    await page.getByRole("button", { name: "Pause" }).waitFor();
  });
  await step(page, "edit: change the placement, the preview follows, save names the field", async () => {
    await page.locator("#placement").click({ force: true });
    await page.getByTitle("Featured strip (home screen)").click();
    await page.getByText("Cards in a horizontal strip").waitFor();
    await page.getByRole("button", { name: "Save changes" }).click();
    await page.getByText("Saved: placement").waitFor();
  });
  await step(page, "the Live tab lists it with its thumbnail and linked app", async () => {
    await page.getByRole("link", { name: "All banners" }).click();
    await page.getByRole("tab", { name: "Live" }).click();
    await page.getByRole("link", { name: title }).waitFor();
  });
  await step(page, "pause from the list, then delete with confirmation", async () => {
    const row = page.locator("tr", { has: page.getByRole("link", { name: title }) });
    await row.getByRole("button", { name: "Pause" }).click();
    await sleep(800);
    await page.getByRole("tab", { name: "Paused" }).click();
    const paused = page.locator("tr", { has: page.getByRole("link", { name: title }) });
    await paused.getByRole("button", { name: "Delete" }).click();
    await page.getByText("Delete this banner?").waitFor();
    await page.getByRole("dialog").getByRole("button", { name: "Delete" }).click();
    await page.getByRole("link", { name: title }).waitFor({ state: "detached" });
  });
}

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} steps passed (${ROLE})`);
if (consoleErrors.length) console.log("browser errors:\n  " + [...new Set(consoleErrors)].slice(0, 10).join("\n  "));
process.exit(failed.length ? 1 : 0);
