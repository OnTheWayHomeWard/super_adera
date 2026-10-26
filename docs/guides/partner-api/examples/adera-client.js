// Minimal Adera partner API client. Node 18+, no dependencies.
// Environment: ADERA_BASE, ADERA_KEY_ID, ADERA_PRIVATE_KEY_FILE (PEM, your PRIVATE key)
const crypto = require("node:crypto");
const fs = require("node:fs");

const BASE = process.env.ADERA_BASE.replace(/\/$/, "");
const KEY_ID = process.env.ADERA_KEY_ID;
const PRIVATE_KEY = crypto.createPrivateKey(fs.readFileSync(process.env.ADERA_PRIVATE_KEY_FILE));

const sha256b64 = (buf) => crypto.createHash("sha256").update(buf).digest("base64");
const rs256Sign = (canonical) => crypto.sign("sha256", Buffer.from(canonical), PRIVATE_KEY).toString("base64"); // RSASSA-PKCS1-v1_5

// path includes the query string exactly as it goes on the wire
async function call(method, path, body) {
  const raw = body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(body));
  const ts = String(Math.floor(Date.now() / 1000));
  const canonical = ["ADERA-SIG-1", ts, method.toUpperCase(), path, sha256b64(raw)].join("\n");
  const signature = rs256Sign(canonical);
  const res = await fetch(BASE + path, {
    method, body: raw.length ? raw : undefined,
    headers: { "X-Adera-Key-Id": KEY_ID, "X-Adera-Timestamp": ts, "X-Adera-Signature": signature, "Content-Type": "application/json" },
  });
  const text = Buffer.from(await res.arrayBuffer());
  await verifyResponse(res, text, signature);
  return { status: res.status, body: text.length ? JSON.parse(text.toString()) : null };
}

// ---- verifying what Adera sends back (responses and callbacks) ----
const jwkCache = new Map(); // kid -> { key, at }
async function aderaPublicKey(kid) {
  let entry = jwkCache.get(kid);
  if (!entry || Date.now() - entry.at > 3600_000) {
    const { keys } = await (await fetch(BASE + "/.well-known/jwks.json")).json();
    for (const k of keys) jwkCache.set(k.kid, { key: crypto.createPublicKey({ key: k, format: "jwk" }), at: Date.now() });
    entry = jwkCache.get(kid);
  }
  if (!entry) throw new Error("unknown Adera key id " + kid);
  return entry.key;
}

async function check(canonical, h) { // h: function(name) -> header value
  if (Math.abs(Date.now() / 1000 - Number(h("x-adera-timestamp"))) > 300) throw new Error("stale timestamp");
  const key = await aderaPublicKey(h("x-adera-key-id"));
  if (!crypto.verify("sha256", Buffer.from(canonical), key, Buffer.from(h("x-adera-signature"), "base64"))) throw new Error("bad Adera signature");
}

async function verifyResponse(res, rawBody, requestSignature) {
  if (!res.headers.get("x-adera-signature")) // gateway answers (429, 502..504) and framework 404/405/415 are unsigned
    throw new Error(`unsigned answer, HTTP ${res.status}: not produced by the API itself`);
  const canonical = ["ADERA-SIG-1-RESPONSE", res.headers.get("x-adera-timestamp"), String(res.status), sha256b64(rawBody),
    sha256b64(Buffer.from(requestSignature))].join("\n");
  await check(canonical, (n) => res.headers.get(n));
}

// in your callback endpoint: rawBody is the unparsed body (Buffer), headers are Node's lower-cased request headers
async function verifyCallback(pathAndQuery, rawBody, headers) {
  const canonical = ["ADERA-SIG-1", headers["x-adera-timestamp"], "POST", pathAndQuery, sha256b64(rawBody)].join("\n");
  await check(canonical, (n) => headers[n]);
}

module.exports = { call, verifyCallback };
