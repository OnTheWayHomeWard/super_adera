/**
 * Helpers for the live smoke test: a real staff sign-in against the running dev stack, using the dashboard's own
 * Kratos and API client code. Dev only: it reads the emailed code from notification-service's dev endpoint
 * (http://127.0.0.1:8085/dev/messages), which exists only while NOTIFY_DEV_ENDPOINTS=true.
 */
import { createHmac, randomBytes } from "node:crypto";
import zlib from "node:zlib";
import { existsSync, readFileSync, writeFileSync, chmodSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { kratos } from "../../src/lib/kratos";
import { clearSession, getToken, saveSession } from "../../src/lib/session";

export const GATEWAY = process.env.GATEWAY_URL ?? "http://127.0.0.1:9080";
export const DEV_MESSAGES = process.env.DEV_MESSAGES_URL ?? "http://127.0.0.1:8085/dev/messages";
export const KRATOS_ADMIN = process.env.KRATOS_ADMIN_URL ?? "http://127.0.0.1:4434";
const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..", "..", "..");

/** The dashboard's code uses relative URLs ("/admin/api/v1/..."); point them at the gateway. */
export function patchFetch(): void {
  const original = globalThis.fetch;
  globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) =>
    original(typeof input === "string" && input.startsWith("/") ? GATEWAY + input : input, init)) as typeof fetch;
}

export function base32Decode(text: string): Buffer {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of text.toUpperCase().replace(/=+$/, "")) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}

export function totp(secret: string, at = Date.now()): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const digest = createHmac("sha1", base32Decode(secret)).update(counter).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const value = (digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return String(value).padStart(6, "0");
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Kratos refuses a TOTP code it has already seen, so the next one needs the next 30-second window. */
let lastWindow = -1;
export async function freshTotp(secret: string): Promise<string> {
  const window = () => Math.floor(Date.now() / 30000);
  while (window() <= lastWindow) await sleep(1000);
  lastWindow = window();
  return totp(secret);
}

export function stateFile(email: string): string {
  const name = ".staff-totp-" + email.toLowerCase().replace(/[^a-z0-9]/g, "_") + ".json";
  const shared = join(ROOT, "tests", "e2e", name); // written by tests/e2e/staff.py
  return existsSync(shared) ? shared : join(HERE, "..", name.replace(".staff-", ".smoke-"));
}

export function bootstrapEmail(): string {
  if (process.env.STAFF_EMAIL) return process.env.STAFF_EMAIL;
  for (const line of readFileSync(join(ROOT, ".env"), "utf8").split("\n")) {
    if (line.startsWith("BOOTSTRAP_SUPER_ADMIN_EMAIL=")) return line.slice(line.indexOf("=") + 1).trim();
  }
  throw new Error("Set STAFF_EMAIL, or BOOTSTRAP_SUPER_ADMIN_EMAIL in .env");
}

export async function emailedCode(email: string, tries = 20): Promise<string> {
  for (let i = 0; i < tries; i++) {
    const r = await fetch(`${DEV_MESSAGES}?to=${encodeURIComponent(email)}`);
    const list = (await r.json().catch(() => [])) as { subject?: string; body?: string }[];
    if (Array.isArray(list) && list.length) {
      const hit = /\b(\d{6})\b/.exec(`${list[0].subject ?? ""} ${list[0].body ?? ""}`);
      if (hit) return hit[1];
    }
    await sleep(1000);
  }
  throw new Error(`no emailed code arrived for ${email} (is the stack in dev mode?)`);
}

export async function clearDevMessages(): Promise<void> {
  await fetch(DEV_MESSAGES, { method: "DELETE" });
}

export interface SignedIn {
  token: string;
  secret: string;
  enrolled: boolean;
}

/**
 * The dashboard's login, step by step: emailed code, then either enrol the authenticator app (first time) or enter
 * the authenticator code. Leaves the aal2 token in the dashboard's session store.
 */
export async function signIn(email: string): Promise<SignedIn> {
  clearSession();
  await clearDevMessages();
  const flowId = await kratos.startLogin();
  await kratos.sendCode(flowId, email);
  const code = await emailedCode(email);
  const token = await kratos.submitCode(flowId, email, code);
  saveSession({ token, aal: "aal1", email });
  const who = await kratos.whoami(token);
  const file = stateFile(email);
  let secret: string;
  let enrolled = false;
  let finalToken = token;
  if (who.state === "aal1_no_totp") {
    const started = await kratos.startEnrolment(token);
    secret = started.secret;
    await kratos.confirmEnrolment(token, started.flowId, await freshTotp(secret));
    writeFileSync(file, JSON.stringify({ email, secret }), { mode: 0o600 });
    chmodSync(file, 0o600);
    enrolled = true;
  } else {
    if (!existsSync(file)) throw new Error(`${email} has an authenticator app but no secret file at ${file}`);
    secret = JSON.parse(readFileSync(file, "utf8")).secret as string;
    const second = await kratos.startSecondFactor(token);
    finalToken = await kratos.submitSecondFactor(token, second, await freshTotp(secret));
  }
  saveSession({ token: finalToken, aal: "aal2", email });
  return { token: finalToken, secret, enrolled };
}

export function currentToken(): string {
  const t = getToken();
  if (!t) throw new Error("not signed in");
  return t;
}

/** Dev-only cleanup of a throw-away staff identity (Kratos admin API is local-only, never routed by the gateway). */
export async function deleteKratosIdentity(id: string): Promise<void> {
  await fetch(`${KRATOS_ADMIN}/admin/identities/${id}`, { method: "DELETE" });
}

export function randomSuffix(): string {
  return randomBytes(3).toString("hex");
}

/** A tiny valid PNG (a solid square) for the logo upload. */
export function tinyPng(size = 64): Buffer {
  
  const crc = (buf: Buffer) => {
    let c = ~0;
    for (const b of buf) {
      c ^= b;
      for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return ~c >>> 0;
  };
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const sum = Buffer.alloc(4);
    sum.writeUInt32BE(crc(body));
    return Buffer.concat([len, body, sum]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 2; // 8-bit RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(size * 3, 0x3c)]);
  const raw = Buffer.concat(Array.from({ length: size }, () => row));
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}
