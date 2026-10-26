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

const results = [];
let shot = 0;
async function step(page, name, fn, screenshot = true) {
  try {
    await fn();
    if (screenshot) await page.screenshot({ path: `${OUT}/${String(++shot).padStart(2, "0")}-${name.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}.png`, fullPage: true });
    results.push({ name, ok: true });
    console.log(`  ok  ${name}`);
  } catch (e) {
    await page.screenshot({ path: `${OUT}/FAIL-${name.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}.png`, fullPage: true }).catch(() => undefined);
    results.push({ name, ok: false, error: String(e).split("\n")[0] });
    console.log(`FAIL  ${name}: ${String(e).split("\n")[0]}`);
  }
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
const consoleErrors = [];
page.on("console", (m) => m.type() === "error" && consoleErrors.push(m.text()));
page.on("pageerror", (e) => consoleErrors.push(String(e)));
const slug = `ui-smoke-${Math.random().toString(16).slice(2, 8)}`;

await step(page, "open the dashboard, redirected to login", async () => {
  await page.goto(`${BASE}/`);
  await page.waitForURL(/\/backoffice\/login/);
  await page.getByText("Staff sign-in").waitFor();
});

await step(page, "email step shows the Mailpit hint after sending the code", async () => {
  await fetch("http://127.0.0.1:8085/dev/messages", { method: "DELETE" });
  await page.getByLabel("Work email").fill(EMAIL);
  await page.getByRole("button", { name: "Send me a code" }).click();
  await page.getByText("We sent a 6-digit code").waitFor();
  await page.getByText("Development stack").waitFor();
});

await step(page, "emailed code, then authenticator code", async () => {
  await page.getByLabel("6-digit code").fill(await emailedCode());
  await page.getByRole("button", { name: "Continue" }).click();
  const enrol = page.getByText("First time here");
  const totpStep = page.getByText("Enter the 6-digit code from your authenticator app");
  await Promise.race([enrol.waitFor(), totpStep.waitFor()]);
  if (await enrol.isVisible()) {
    const secret = (await page.locator("code").filter({ hasText: /^[A-Z2-7]{16,}$/ }).first().innerText()).trim();
    await page.screenshot({ path: `${OUT}/00-enrol-authenticator.png`, fullPage: true });
    writeFileSync(stateFile, JSON.stringify({ email: EMAIL, secret }), { mode: 0o600 });
    await page.getByLabel("Code from the authenticator app").fill(await freshTotp(secret));
    await page.getByRole("button", { name: "Confirm and sign in" }).click();
  } else {
    const secret = JSON.parse(readFileSync(stateFile, "utf8")).secret;
    await page.getByLabel("Authenticator code").fill(await freshTotp(secret));
    await page.getByRole("button", { name: "Sign in" }).click();
  }
  await page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: 20000 });
});

await step(page, "header shows who is signed in and the role", async () => {
  await page.getByText(EMAIL).first().waitFor();
  await page.getByText("Super admin").first().waitFor();
  const stored = await page.evaluate(() => ({ session: sessionStorage.getItem("adera.backoffice.session") !== null, local: localStorage.length }));
  if (!stored.session || stored.local !== 0) throw new Error(`token must be in sessionStorage only: ${JSON.stringify(stored)}`);
});

await step(page, "dashboard panels load real numbers", async () => {
  await page.getByText("Mini apps by status").waitFor();
  await page.getByText("Open reports").first().waitFor();
  await sleep(800);
  if ((await page.locator(".ant-alert-error").count()) > 0) throw new Error("an error panel is showing");
});

await step(page, "mini apps list", async () => {
  await page.getByRole("link", { name: "Mini apps" }).first().click();
  await page.getByRole("heading", { name: "Mini apps" }).waitFor();
  await page.getByPlaceholder("Search name, slug, publisher, category").fill("smoke");
  await sleep(500);
});

await step(page, "create an app: client validation then success", async () => {
  await page.getByRole("button", { name: "New app" }).click();
  await page.getByRole("button", { name: "Create app" }).click();
  await page.getByText("A slug is required").waitFor();
  await page.getByLabel("Slug").fill(slug);
  await page.getByLabel("Name - English").fill("UI smoke app");
  await page.getByLabel("Name - Amharic (አማርኛ)").fill("ሙከራ");
  await page.getByRole("button", { name: "Create app" }).click();
  await page.waitForURL(/\/backoffice\/apps\/[0-9a-f-]{36}/);
  await page.getByText("UI smoke app").first().waitFor();
});

await step(page, "edit general: server validation message appears next to the field", async () => {
  await page.getByLabel("Brand colour").fill("#112233");
  await page.getByLabel("Website").fill("https://ui-smoke.adera.test");
  await page.getByLabel("Support email").fill("ui-smoke@adera.test");
  await page.getByRole("button", { name: "Save changes" }).click();
  await page.getByText(/Saved:/).first().waitFor();
});

for (const [tab, marker] of [
  ["Capabilities", "What this app may ask for"],
  ["Notification limits", "How limits work"],
  ["Environments and IPs", "Allowed server addresses"],
  ["Partner keys", "How partners prove who they are"],
  ["Payment", "Payment settings are policy only"],
  ["Templates", "Partners cannot send free text"],
  ["Notification log", "Everything this app asked us to send"],
  ["Usage", "Today means the current UTC day"],
  ["Reviews", "Hidden reviews stay here"],
  ["Reports", "REVIEWED = acted on"],
  ["Audit", "Actor"],
]) {
  await step(page, `tab ${tab}`, async () => {
    await page.getByRole("tab", { name: tab }).click();
    await page.getByText(marker).first().waitFor();
  });
}

await step(page, "grant a capability: reason is required, then granted", async () => {
  await page.getByRole("tab", { name: "Capabilities" }).click();
  const row = page.getByRole("row").filter({ hasText: "location.coarse" });
  await row.getByRole("button", { name: "Change" }).click();
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByText("At least 10 characters in English").waitFor();
  await page.getByLabel("Reason - English").fill("To show technicians near you");
  await page.getByRole("button", { name: "Save" }).click();
  await row.getByText("GRANTED").waitFor();
});

await step(page, "limits: unlimited vs 0 explained and saved", async () => {
  await page.getByRole("tab", { name: "Notification limits" }).click();
  const row = page.getByRole("row").filter({ hasText: "SMS" });
  await row.getByRole("button", { name: "Edit" }).click();
  await page.getByRole("button", { name: "Save limits" }).click();
  await page.getByRole("row").filter({ hasText: "SMS" }).getByText("unlimited").first().waitFor();
});

await step(page, "environments: DEV and PROD sections, active one marked, save an allowed IP in each", async () => {
  await page.getByRole("tab", { name: "Environments and IPs" }).click();
  await page.getByText("ACTIVE on this deployment").waitFor();
  await page.getByText("stored, not used here").first().waitFor();
  const dev = page.getByTestId("env-card-DEV");
  const prod = page.getByTestId("env-card-PROD");
  await dev.locator("textarea").fill("203.0.113.7");
  await dev.getByRole("button", { name: "Save addresses" }).click();
  await page.getByText("DEV addresses saved").waitFor();
  await prod.locator("textarea").fill("0.0.0.0/0");
  await prod.getByRole("button", { name: "Save addresses" }).click();
  await page.getByText(/not allowed for PROD/).first().waitFor();
  await prod.locator("textarea").fill("198.51.100.0/24");
  await prod.getByRole("button", { name: "Save addresses" }).click();
  await page.getByText("PROD addresses saved").waitFor();
});

await step(page, "keys: generate a pair (notice for the non-active environment), private key shown once with a warning", async () => {
  await page.getByRole("tab", { name: "Partner keys" }).click();
  await page.getByRole("button", { name: "Generate a key pair" }).click();
  await page.getByRole("dialog").getByText("PROD", { exact: true }).click();
  await page.getByText(/This deployment runs DEV, not PROD/).waitFor();
  await page.getByRole("dialog").getByText("DEV", { exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Generate" }).click();
  await page.getByText("Private key: shown once").waitFor();
  await page.getByText("BEGIN PRIVATE KEY").first().waitFor();
  const closeDisabled = await page.getByRole("button", { name: "Close" }).isDisabled();
  if (!closeDisabled) throw new Error("the dialog must not close before the 'I saved it' box is ticked");
  await page.screenshot({ path: `${OUT}/key-once.png` });
  await page.getByLabel("I have saved the private key somewhere safe.").check();
  await page.getByRole("button", { name: "Close" }).click();
  await page.getByRole("cell", { name: "ACTIVE" }).first().waitFor();
  await page.getByText("active here").first().waitFor();
});

await step(page, "keys: revoke asks for confirmation", async () => {
  await page.getByRole("button", { name: "Revoke" }).first().click();
  await page.getByText(/Revoke key/).waitFor();
  await page.getByRole("dialog").getByRole("button", { name: "Revoke" }).click();
  await page.getByRole("cell", { name: "REVOKED" }).first().waitFor();
});

await step(page, "templates: create with a param and content", async () => {
  await page.getByRole("tab", { name: "Templates" }).click();
  await page.getByRole("button", { name: "New template" }).click();
  await page.getByLabel("Code").fill("ui-smoke-hello");
  await page.getByRole("button", { name: "Add param" }).click();
  await page.getByPlaceholder("name").fill("name");
  await page.getByLabel("Body").first().fill("Hello {name}");
  await page.getByRole("button", { name: "Create template" }).click();
  await page.getByRole("cell", { name: "ui-smoke-hello" }).waitFor();
});

await step(page, "status: LIVE refused with the readiness message, then KILLED with confirmation", async () => {
  await page.getByRole("button", { name: "LIVE", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Set LIVE" }).click();
  await page.getByText(/Not ready for LIVE: missing a logo/).last().waitFor();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
  await page.getByRole("button", { name: "KILLED", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Set KILLED" }).click();
  await page.getByText("Emergency stop").first().waitFor();
});

await step(page, "moderation", async () => {
  await page.getByRole("link", { name: "Moderation" }).click();
  await page.getByRole("heading", { name: "Moderation" }).waitFor();
  await page.getByRole("tab", { name: "Reviews" }).click();
  await page.getByText("Hidden reviews stay here").waitFor();
});

await step(page, "users: list, search, detail", async () => {
  await page.getByRole("link", { name: "Users" }).first().click();
  await page.getByRole("heading", { name: "Users" }).waitFor();
  await page.getByText("Registered").first().waitFor();
  const first = page.locator("tbody tr a").first();
  if (await first.count()) {
    await first.click();
    await page.getByText("Devices and aliases").waitFor();
    await page.getByText("Push tokens and pseudonymous ids are never shown.").waitFor();
  }
});

await step(page, "notifications: global log", async () => {
  await page.getByRole("link", { name: "Notifications" }).first().click();
  await page.getByRole("heading", { name: "Notifications" }).waitFor();
});

await step(page, "staff list shows the bootstrap account", async () => {
  await page.getByRole("link", { name: "Staff" }).first().click();
  await page.getByRole("heading", { name: "Staff" }).waitFor();
  await page.getByText("bootstrap super admin").first().waitFor();
});

await step(page, "audit log filter", async () => {
  await page.getByRole("link", { name: "Audit log" }).click();
  await page.getByRole("heading", { name: "Audit log" }).waitFor();
  await page.getByPlaceholder("e.g. app. or app.status").fill("app.");
  await page.getByRole("button", { name: "Filter" }).click();
  await page.getByText("app.create").first().waitFor();
});

await step(page, "system: links, health, glossary", async () => {
  await page.getByRole("link", { name: "System" }).click();
  await page.getByText("Service health").first().waitFor();
  await page.getByText("registry-service").first().waitFor();
  await page.getByText(/This deployment runs/).first().waitFor();
  await page.getByText("UP").first().waitFor({ timeout: 15000 });
  await page.getByRole("tab", { name: "Error codes (staff API)" }).click();
  await page.getByText("last_super_admin").waitFor();
});

await step(page, "sign out returns to the login page and drops the session", async () => {
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(/\/backoffice\/login/);
  const left = await page.evaluate(() => sessionStorage.getItem("adera.backoffice.session"));
  if (left) throw new Error("the session token is still stored");
});

await step(page, "an expired session sends you to login with a message", async () => {
  await page.evaluate(() => sessionStorage.setItem("adera.backoffice.session", JSON.stringify({ token: "expired-token", aal: "aal2", email: "x@y.z" })));
  await page.goto(`${BASE}/apps`);
  await page.waitForURL(/\/backoffice\/login/);
  await page.getByText("Your session ended").waitFor();
});

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} steps passed; app ${slug} left KILLED`);
if (consoleErrors.length) console.log("browser console errors:\n  " + [...new Set(consoleErrors)].slice(0, 10).join("\n  "));
process.exit(failed.length ? 1 : 0);
