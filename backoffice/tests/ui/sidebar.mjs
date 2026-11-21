// Browser check (Playwright): the side menu stays in view while a long page scrolls. Run with:  SCRIPT=sidebar.mjs ROLE=VIEWER bash tests/ui/run.sh
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
for (const vp of [{ width: 1440, height: 700 }, { width: 1100, height: 600 }]) {
  const page = await (await browser.newContext({ viewport: vp })).newPage();
  await step(page, `sign in (${vp.width}x${vp.height})`, async () => {
    await fetch("http://127.0.0.1:8085/dev/messages", { method: "DELETE" });
    await page.goto(`${BASE}/login`);
    await page.getByLabel("Work email").fill(EMAIL);
    await page.getByRole("button", { name: "Send me a code" }).click();
    await page.getByLabel("6-digit code").fill(await emailedCode());
    await page.getByRole("button", { name: "Continue" }).click();
    const secret = JSON.parse(readFileSync(stateFile, "utf8")).secret;
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
  await step(page, `side menu stays in view while the page scrolls (${vp.width}x${vp.height})`, async () => {
    await page.getByRole("link", { name: "Audit" }).first().click().catch(() => undefined);
    await sleep(1500);
    const tall = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
    if (tall < 200) await page.evaluate(() => { const d = document.createElement("div"); d.style.height = "3000px"; document.querySelector(".ant-layout-content")?.appendChild(d); });
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await sleep(500);
    const r = await page.evaluate(() => {
      const s = document.querySelector(".ant-layout-has-sider > .ant-layout-sider");
      const b = s.getBoundingClientRect();
      return { scrollY: window.scrollY, top: b.top, height: b.height, inner: window.innerHeight };
    });
    await page.screenshot({ path: `${OUT}/sidebar-${vp.width}.png` });
    if (r.scrollY < 100) throw new Error(`page did not scroll (${JSON.stringify(r)})`);
    if (Math.abs(r.top) > 1 || Math.abs(r.height - r.inner) > 1) throw new Error(`side menu moved: ${JSON.stringify(r)}`);
  });
  await page.close();
}
await browser.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`${results.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
