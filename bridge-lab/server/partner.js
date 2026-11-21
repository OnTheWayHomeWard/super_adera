// Signed partner calls (ADERA-SIG-1, RS256 with the app's private key), Adera's response signature check
// (ADERA-SIG-1-RESPONSE) and the callback signature check. Same scheme as docs/guides/partner-api/examples/adera-client.js.
"use strict";
const crypto = require("node:crypto");

const sha256b64 = (buf) => crypto.createHash("sha256").update(buf).digest("base64");

function createPartner({ base, keyId, privateKeyPem, jwks, fetchImpl = fetch }) {
  const privateKey = privateKeyPem ? crypto.createPrivateKey(privateKeyPem) : null;
  // a second, throw-away key: a signature that cannot verify against the registered public key
  const wrongKey = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;

  const sign = (canonical, key = privateKey) => crypto.sign("sha256", Buffer.from(canonical), key).toString("base64"); // RSASSA-PKCS1-v1_5

  async function checkAderaSignature(canonical, get) {
    const ts = Number(get("x-adera-timestamp"));
    if (!Number.isFinite(ts) || Math.abs(Date.now() / 1000 - ts) > 300) throw new Error("stale or missing X-Adera-Timestamp");
    const key = await jwks.key(get("x-adera-key-id"));
    const sig = get("x-adera-signature");
    if (!sig) throw new Error("no X-Adera-Signature");
    if (!crypto.verify("sha256", Buffer.from(canonical), key, Buffer.from(sig, "base64"))) throw new Error("bad Adera signature");
  }

  /**
   * One signed call. mode: normal | bad-signature | stale-timestamp | unknown-key | missing-signature
   * Returns everything the backend log shows (never the private key; the signature only shortened).
   */
  async function call(method, path, body, mode = "normal") {
    if (!privateKey) throw new Error("no partner key configured: run bridge-lab/seed.py");
    const raw = body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(body));
    let ts = Math.floor(Date.now() / 1000);
    if (mode === "stale-timestamp") ts -= 3600;
    const canonical = ["ADERA-SIG-1", String(ts), method.toUpperCase(), path, sha256b64(raw)].join("\n");
    const signature = sign(canonical, mode === "bad-signature" ? wrongKey : privateKey);
    const traceId = crypto.randomBytes(16).toString("hex");
    const headers = {
      "X-Adera-Key-Id": mode === "unknown-key" ? "ak_dev_doesnotexist" : keyId,
      "X-Adera-Timestamp": String(ts),
      "X-Adera-Signature": signature,
      "Content-Type": "application/json",
      traceparent: `00-${traceId}-${crypto.randomBytes(8).toString("hex")}-01`,
    };
    if (mode === "missing-signature") delete headers["X-Adera-Signature"];
    const started = Date.now();
    let res;
    let text;
    try {
      res = await fetchImpl(base + path, { method, body: raw.length ? raw : undefined, headers });
      text = Buffer.from(await res.arrayBuffer());
    } catch (e) {
      return { method, path, mode, error: "network: " + e.message, ms: Date.now() - started, traceId, requestHeaders: shown(headers) };
    }
    const ms = Date.now() - started;
    let parsed = null;
    try { parsed = text.length ? JSON.parse(text.toString("utf8")) : null; } catch { parsed = { _raw: text.toString("utf8").slice(0, 500) }; }
    const responseSignature = { present: !!res.headers.get("x-adera-signature"), verified: false, detail: "" };
    if (responseSignature.present) {
      try {
        const canon = ["ADERA-SIG-1-RESPONSE", res.headers.get("x-adera-timestamp"), String(res.status), sha256b64(text), sha256b64(Buffer.from(signature))].join("\n");
        await checkAderaSignature(canon, (n) => res.headers.get(n));
        responseSignature.verified = true;
        responseSignature.detail = "verified with " + res.headers.get("x-adera-key-id");
      } catch (e) {
        responseSignature.detail = e.message;
      }
    } else {
      responseSignature.detail = "unsigned answer (gateway or framework answer, not the API itself)";
    }
    return {
      method, path, mode, status: res.status, ms, body: parsed,
      requestHeaders: shown(headers),
      responseHeaders: pick(res.headers, ["x-request-id", "x-adera-key-id", "x-adera-timestamp", "x-ratelimit-limit", "x-ratelimit-remaining", "content-type"]),
      requestId: res.headers.get("x-request-id"), traceId, responseSignature,
    };
  }

  // a callback: rawBody Buffer, pathAndQuery exactly as received, headers lower-cased (node)
  async function verifyCallback(pathAndQuery, rawBody, headers) {
    const canonical = ["ADERA-SIG-1", headers["x-adera-timestamp"], "POST", pathAndQuery, sha256b64(rawBody)].join("\n");
    await checkAderaSignature(canonical, (n) => headers[n]);
  }

  return { call, verifyCallback, signWith: sign, hasKey: !!privateKey };
}

function shown(headers) {
  const out = { ...headers };
  if (out["X-Adera-Signature"]) out["X-Adera-Signature"] = out["X-Adera-Signature"].slice(0, 16) + "... (" + headers["X-Adera-Signature"].length + " chars)";
  return out;
}

function pick(h, names) {
  const out = {};
  for (const n of names) if (h.get(n) != null) out[n] = h.get(n);
  return out;
}

// personal data in the backend log: masked (the page step still shows the full answer)
function mask(value) {
  if (Array.isArray(value)) return value.map(mask);
  if (!value || typeof value !== "object") return value;
  const out = {};
  for (const [k, v] of Object.entries(value)) {
    if (k === "phone" && typeof v === "string") out[k] = v.length > 6 ? v.slice(0, 5) + "*".repeat(Math.max(1, v.length - 7)) + v.slice(-2) : "***";
    else if (k === "email" && typeof v === "string") out[k] = v.replace(/^(.)[^@]*(@.*)$/, "$1***$2");
    else if (k === "fullName" && typeof v === "string") out[k] = v.split(/\s+/).map((w) => w[0] + "***").join(" ");
    else out[k] = mask(v);
  }
  return out;
}

module.exports = { createPartner, mask, sha256b64 };
