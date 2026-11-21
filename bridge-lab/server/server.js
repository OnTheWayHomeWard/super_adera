// Bridge Lab backend: static front end, launch-token verification, signed partner calls, callback receiver,
// per-session log with a server-sent-event stream. Node 22, no dependencies.
"use strict";
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { Logbook, SHARED } = require("./logbook");
const { createJwks } = require("./jwks");
const { verifyLaunchToken, tamperedVariants } = require("./launch");
const { createPartner, mask } = require("./partner");

// ------------------------------------------------------------------------------------------------ configuration
const ROOT = path.resolve(__dirname, "..");
const CONFIG_FILE = process.env.BRIDGE_LAB_CONFIG || path.join(ROOT, ".local", "config.json");
let fileConfig = {};
try {
  fileConfig = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
} catch (e) {
  console.warn("no config file at " + CONFIG_FILE + " (" + e.code + "): run bridge-lab/seed.py; partner calls are disabled until then");
}
const env = process.env;
const CFG = {
  base: (env.ADERA_BASE || fileConfig.gatewayInternal || "http://apisix:9080").replace(/\/$/, ""),
  env: env.ADERA_ENV || fileConfig.env || "dev",
  appId: env.ADERA_APP_ID || fileConfig.appId || null,
  slug: fileConfig.slug || "bridge-lab",
  keyId: env.ADERA_KEY_ID || fileConfig.keyId || null,
  privateKeyFile: env.ADERA_PRIVATE_KEY_FILE || (fileConfig.privateKeyFile ? path.resolve(path.dirname(CONFIG_FILE), fileConfig.privateKeyFile) : null),
  port: Number(env.PORT || 8095),
  altPort: Number(env.ALT_PORT || 8096),
  publicUrl: env.BRIDGE_LAB_PUBLIC_URL || "http://localhost:8095",
  altUrl: env.BRIDGE_LAB_ALT_URL || "http://localhost:8096",
  foreignUrl: env.BRIDGE_LAB_FOREIGN_URL || "http://127.0.0.1:8095",
  callbackPath: env.CALLBACK_PATH || "/adera/events",
  callbackSkew: Number(env.CALLBACK_MAX_SKEW_SECONDS || 300),
  grafanaUrl: (env.GRAFANA_URL || "").replace(/\/$/, ""),
  docsUrl: env.DOCS_URL || "/docs",
  docsDir: env.DOCS_DIR || path.join(ROOT, "docs"),
  publicDir: path.join(ROOT, "public"),
};
let privateKeyPem = null;
if (CFG.privateKeyFile) {
  try { privateKeyPem = fs.readFileSync(CFG.privateKeyFile, "utf8"); } catch (e) { console.warn("cannot read the partner key " + CFG.privateKeyFile + ": " + e.code); }
}

const log = new Logbook();
const jwks = createJwks(CFG.base);
const partner = createPartner({ base: CFG.base, keyId: CFG.keyId, privateKeyPem, jwks });
const seenJti = new Map();
const sessions = new Map(); // labSession -> { key, alias, claims, at }
const callbacks = []; // { seq, key, event, alias, verified, idempotencyKey, duplicate, body, headers, at }
const seenIdempotency = new Set();
const externalIdOwner = new Map(); // externalId -> bucket key (notification.status callbacks carry no alias)
const lastCallbackRaw = { value: null }; // for the duplicate self-test
const SYNTHETIC = /^u_p_test_[a-z]+$/;

// ------------------------------------------------------------------------------------------------ helpers
function send(res, status, body, headers = {}) {
  const data = typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, { "Content-Type": typeof body === "string" ? "text/plain; charset=utf-8" : "application/json", "Cache-Control": "no-store", ...headers });
  res.end(data);
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) { reject(Object.assign(new Error("body too large"), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

async function readJson(req) {
  const raw = await readBody(req);
  if (!raw.length) return {};
  try { return JSON.parse(raw.toString("utf8")); } catch { throw Object.assign(new Error("body is not JSON"), { status: 400 }); }
}

function session(req, url) {
  const id = req.headers["x-lab-session"] || url.searchParams.get("s");
  if (!id || !/^[A-Za-z0-9_-]{8,64}$/.test(id)) return null;
  let s = sessions.get(id);
  if (!s) {
    s = { id, key: "anon-" + id.slice(0, 8), alias: null, claims: null, at: Date.now() };
    sessions.set(id, s);
  }
  return s;
}

function aliasFor(s, requested) {
  if (requested && (SYNTHETIC.test(requested) || requested === "u_p_0000000000000000000000000000dead")) return requested; // DEV test ids / unknown-alias probe
  if (!s || !s.alias) throw Object.assign(new Error("no verified launch token in this session: open Bridge Lab from the super app (or verify a token first)"), { status: 409, code: "no_launch" });
  return s.alias;
}

async function partnerCall(s, title, method, p, body, mode) {
  const r = await partner.call(method, p, body, mode);
  log.add(s ? s.key : SHARED, "partner", `${method} ${p.replace(/\?.*$/, "")}${mode && mode !== "normal" ? " [" + mode + "]" : ""} -> ${r.status ?? r.error}`, {
    what: title, request: { method, path: p, headers: r.requestHeaders, body: body ? mask(body) : undefined },
    status: r.status, error: r.error, ms: r.ms, response: { headers: r.responseHeaders, body: mask(r.body) },
    responseSignature: r.responseSignature, requestId: r.requestId, traceId: r.traceId,
  });
  return r;
}

const MIME = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon", ".txt": "text/plain; charset=utf-8", ".py": "text/plain; charset=utf-8" };

function serveFile(res, dir, rel) {
  const file = path.normalize(path.join(dir, rel));
  if (!file.startsWith(dir)) return send(res, 404, { error: "not_found" });
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      if (!err && st.isDirectory()) return serveFile(res, dir, path.join(rel, "index.html"));
      return send(res, 404, { error: "not_found" });
    }
    res.writeHead(200, { "Content-Type": MIME[path.extname(file)] || "application/octet-stream", "Cache-Control": "no-cache" });
    fs.createReadStream(file).pipe(res);
  });
}

function publicConfig(req) {
  return {
    appId: CFG.appId, slug: CFG.slug, env: CFG.env, keyConfigured: partner.hasKey, keyId: CFG.keyId,
    publicUrl: CFG.publicUrl, altUrl: CFG.altUrl, foreignUrl: CFG.foreignUrl, entryUrl: fileConfig.entryUrl || CFG.publicUrl + "/?entry=1",
    allowedOrigins: fileConfig.allowedOrigins || [CFG.publicUrl, CFG.altUrl], callbackUrl: fileConfig.callbackUrl || null,
    capabilities: fileConfig.capabilities || [], templates: fileConfig.templates || {}, grafanaUrl: CFG.grafanaUrl, docsUrl: CFG.docsUrl,
    servedFrom: (req.headers["x-forwarded-proto"] || "http") + "://" + req.headers.host,
  };
}

// ------------------------------------------------------------------------------------------------ the callback receiver
async function receiveCallback(req, res, url) {
  const raw = await readBody(req);
  const h = req.headers;
  const pathAndQuery = url.pathname + url.search;
  let verified = false;
  let detail;
  try {
    await partner.verifyCallback(pathAndQuery, raw, h);
    verified = true;
    detail = "signature verified with " + h["x-adera-key-id"] + " (JWKS), timestamp within " + CFG.callbackSkew + " s";
  } catch (e) {
    detail = e.message;
  }
  let body = null;
  try { body = JSON.parse(raw.toString("utf8")); } catch { body = { _raw: raw.toString("utf8").slice(0, 500) }; }
  const event = h["x-adera-event"] || (body && body.event) || "?";
  const idem = h["idempotency-key"] || null;
  const duplicate = verified && idem ? seenIdempotency.has(idem) : false;
  if (verified && idem) seenIdempotency.add(idem);
  const alias = body && typeof body.alias === "string" ? body.alias : null;
  const owner = alias ? [...sessions.values()].find((s) => s.alias === alias) : null;
  const key = owner ? owner.key : alias ? alias : (body && body.externalId && externalIdOwner.get(body.externalId)) || SHARED;
  const headers = {};
  for (const n of ["x-adera-event", "x-adera-key-id", "x-adera-timestamp", "x-adera-signature", "idempotency-key", "user-agent", "content-type", "x-lab-selftest"]) {
    if (h[n]) headers[n] = n === "x-adera-signature" ? h[n].slice(0, 16) + "..." : h[n];
  }
  const rec = { seq: callbacks.length + 1, key, event, alias, verified, detail, idempotencyKey: idem, duplicate, body, headers, at: new Date().toISOString(), selftest: !!h["x-lab-selftest"] };
  callbacks.push(rec);
  if (callbacks.length > 2000) callbacks.splice(0, 500);
  if (verified && !h["x-lab-selftest"]) lastCallbackRaw.value = { raw, headers: { ...h }, pathAndQuery };
  log.add(key, "callback", `${event}${body && body.capability ? " " + body.capability : ""}${body && body.state ? " " + body.state : ""}${body && body.status ? " " + body.status : ""} -> ${verified ? (duplicate ? "duplicate (ignored)" : "verified") : "REJECTED"}`, {
    callback: rec, verified, detail,
  });
  if (!verified) return send(res, 401, { error: "invalid_signature", detail });
  res.writeHead(204).end();
}

// ------------------------------------------------------------------------------------------------ API
async function api(req, res, url) {
  const p = url.pathname;
  const s = session(req, url);

  if (p === "/api/config" && req.method === "GET") return send(res, 200, publicConfig(req));

  if (p === "/api/session" && req.method === "POST") {
    if (!s) return send(res, 400, { error: "need X-Lab-Session (8 to 64 of A-Z a-z 0-9 _ -)" });
    return send(res, 200, { key: s.key, alias: s.alias, claims: s.claims });
  }

  if (p === "/api/launch/verify" && req.method === "POST") {
    const b = await readJson(req);
    const probe = !!b.probe;
    const expectAud = b.expectAud || CFG.appId;
    const result = await verifyLaunchToken(b.token, { jwks, appId: expectAud, env: CFG.env, seenJti: probe ? null : seenJti });
    const key = s ? s.key : SHARED;
    if (result.valid && !probe && s) {
      const old = s.key;
      s.alias = result.claims.sub;
      s.claims = result.claims;
      s.key = result.claims.sub;
      log.merge(old, s.key);
    }
    log.add(result.valid && !probe && s ? s.key : key, "launch", `${probe ? "probe" : "launch token"}${b.label ? " (" + b.label + ")" : ""} -> ${result.valid ? "VALID" : "REJECTED: " + result.reason}`, {
      verdict: result.valid ? "valid" : "rejected", reason: result.reason, header: result.header, claims: result.claims, checks: result.checks, replay: result.replay,
      tokenPreview: typeof b.token === "string" ? b.token.slice(0, 24) + "... (" + b.token.length + " chars)" : String(b.token),
    });
    return send(res, 200, { ...result, key: s ? s.key : null });
  }

  if (p === "/api/launch/tampered" && req.method === "POST") {
    const b = await readJson(req);
    if (typeof b.token !== "string") return send(res, 400, { error: "token required" });
    const out = [];
    for (const v of tamperedVariants(b.token)) {
      const r = await verifyLaunchToken(v.token, { jwks, appId: v.expectAud || CFG.appId, env: CFG.env, seenJti: null });
      const failed = r.checks.find((c) => !c.ok && c.mandatory);
      out.push({ name: v.name, expectFailingCheck: v.expect, valid: r.valid, reason: r.reason, failingCheck: failed ? failed.check : null, ok: !r.valid && failed && failed.check === v.expect });
      log.add(s ? s.key : SHARED, "launch", `tampered token "${v.name}" -> ${r.valid ? "ACCEPTED (bad!)" : "rejected: " + r.reason}`, { verdict: r.valid ? "valid" : "rejected", reason: r.reason, checks: r.checks });
    }
    return send(res, 200, { probes: out });
  }

  if (p === "/api/partner/profile" && req.method === "POST") {
    const b = await readJson(req);
    const alias = aliasFor(s, b.alias);
    const fields = Array.isArray(b.fields) && b.fields.length ? "?fields=" + b.fields.map(encodeURIComponent).join(",") : "";
    return send(res, 200, await partnerCall(s, "profile", "GET", "/v1/users/" + alias + "/profile" + fields, undefined, b.mode || "normal"));
  }

  if (p === "/api/partner/templates" && req.method === "GET") {
    return send(res, 200, await partnerCall(s, "templates", "GET", "/v1/templates"));
  }

  if (p === "/api/partner/notify" && req.method === "POST") {
    const b = await readJson(req);
    const alias = aliasFor(s, b.alias);
    const tpl = b.templateId || (fileConfig.templates || {})[b.kind || "inapp"];
    if (!tpl) return send(res, 409, { error: "no template configured for kind " + b.kind + ": run seed.py" });
    const externalId = b.externalId || `lab-${(b.kind || "inapp")}-${Date.now().toString(36)}-${crypto.randomBytes(3).toString("hex")}`;
    const body = { externalId, userId: alias, templateId: tpl, params: b.params || { text: "Bridge Lab test " + new Date().toISOString().slice(11, 19) } };
    if (b.data) body.data = b.data;
    if (b.callback !== false) body.callbackUrl = fileConfig.callbackUrl || null;
    if (!body.callbackUrl) delete body.callbackUrl;
    if (b.ttlSeconds) body.ttlSeconds = b.ttlSeconds;
    if (s) externalIdOwner.set(externalId, s.key);
    return send(res, 200, await partnerCall(s, "send notification", "POST", "/v1/notifications", body));
  }

  const m = p.match(/^\/api\/partner\/notifications\/([A-Za-z0-9._:-]{1,100})$/);
  if (m && req.method === "GET") {
    return send(res, 200, await partnerCall(s, "notification status", "GET", "/v1/notifications/" + m[1]));
  }

  if (p === "/api/callbacks" && req.method === "GET") {
    const since = Number(url.searchParams.get("since") || 0);
    const key = s ? s.key : SHARED;
    return send(res, 200, { last: callbacks.length ? callbacks[callbacks.length - 1].seq : 0, callbacks: callbacks.filter((c) => c.seq > since && (c.key === key || c.key === SHARED || (s && s.alias && c.alias === s.alias))) });
  }

  if (p === "/api/callbacks/selftest" && req.method === "POST") {
    const b = await readJson(req);
    const target = `http://127.0.0.1:${CFG.port}${CFG.callbackPath}`;
    const body = Buffer.from(JSON.stringify({ event: "user.consent_changed", alias: s && s.alias ? s.alias : "u_p_selftest", capability: "user.full_name", state: "GRANTED", at: new Date().toISOString(), environment: "DEV" }));
    const ts = String(Math.floor(Date.now() / 1000));
    let headers;
    if (b.kind === "duplicate") {
      if (!lastCallbackRaw.value) return send(res, 409, { error: "no real callback received yet: change a permission first" });
      const v = lastCallbackRaw.value;
      const hh = {};
      for (const n of ["x-adera-event", "x-adera-key-id", "x-adera-timestamp", "x-adera-signature", "idempotency-key", "content-type"]) if (v.headers[n]) hh[n] = v.headers[n];
      const r = await fetch(`http://127.0.0.1:${CFG.port}${v.pathAndQuery}`, { method: "POST", body: v.raw, headers: { ...hh, "x-lab-selftest": "duplicate" } });
      const rec = callbacks[callbacks.length - 1];
      return send(res, 200, { status: r.status, duplicate: !!(rec && rec.duplicate), verified: !!(rec && rec.verified) });
    }
    if (b.kind === "unsigned") headers = { "Content-Type": "application/json", "X-Adera-Event": "user.consent_changed" };
    else {
      // signed with a key that is not Adera's but claims Adera's key id
      const kid = jwks.kids()[0] || "adera-sig-202610";
      const canonical = ["ADERA-SIG-1", ts, "POST", CFG.callbackPath, crypto.createHash("sha256").update(body).digest("base64")].join("\n");
      const fake = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;
      headers = { "Content-Type": "application/json", "X-Adera-Event": "user.consent_changed", "X-Adera-Key-Id": kid, "X-Adera-Timestamp": ts,
        "X-Adera-Signature": crypto.sign("sha256", Buffer.from(canonical), fake).toString("base64"), "Idempotency-Key": crypto.randomUUID() };
    }
    const r = await fetch(target, { method: "POST", body, headers: { ...headers, "x-lab-selftest": b.kind || "forged" } });
    return send(res, 200, { status: r.status, body: await r.text() });
  }

  if (p === "/api/log" && req.method === "GET") {
    return send(res, 200, { key: s ? s.key : SHARED, entries: log.history(s ? s.key : SHARED, Number(url.searchParams.get("since") || 0)) });
  }

  if (p === "/api/log/stream" && req.method === "GET") {
    const key = s ? s.key : SHARED;
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive", "X-Accel-Buffering": "no" });
    res.write("retry: 3000\n\n");
    res.write("event: hello\ndata: " + JSON.stringify({ key }) + "\n\n");
    for (const e of log.history(key, Number(url.searchParams.get("since") || 0))) res.write("event: entry\ndata: " + JSON.stringify(e) + "\n\n");
    const off = log.subscribe(key, res);
    const ping = setInterval(() => res.write(": ping\n\n"), 20000);
    req.on("close", () => { off(); clearInterval(ping); });
    return;
  }

  // the origin probe page (served on the second allowed origin and on a foreign origin) reports what it found
  if (p === "/api/probe" && req.method === "POST") {
    const b = await readJson(req);
    const rec = { which: b.which === "present" ? "present" : "absent", origin: String(b.origin || "").slice(0, 100), hasAdera: !!b.hasAdera, hasChannel: !!b.hasChannel,
      posted: !!b.posted, answered: !!b.answered, answer: b.answer || null, frame: !!b.frame, at: new Date().toISOString() };
    if (s) (s.probes = s.probes || []).push(rec);
    log.add(s ? s.key : SHARED, "probe", `origin probe on ${rec.origin}${rec.frame ? " (iframe)" : ""}: window.adera ${rec.hasAdera ? "PRESENT" : "absent"}, AderaBridge ${rec.hasChannel ? "present" : "absent"}${rec.posted ? ", answer: " + (rec.answered ? "YES" : "none") : ""}`, { probe: rec });
    return send(res, 200, { ok: true });
  }
  if (p === "/api/probe" && req.method === "GET") return send(res, 200, { probes: (s && s.probes) || [] });

  // the page uploads its report (results + bridge log) so it can be downloaded on a laptop (WebView downloads often fail)
  if (p === "/api/report" && req.method === "POST") {
    if (!s) return send(res, 400, { error: "no session" });
    const raw = await readBody(req, 2 * 1024 * 1024);
    try { s.report = JSON.parse(raw.toString("utf8")); } catch { return send(res, 400, { error: "not JSON" }); }
    s.report.uploadedAt = new Date().toISOString();
    log.add(s.key, "note", "report uploaded: " + CFG.publicUrl + "/api/report?s=" + s.id + "&format=md (and format=json)", {});
    return send(res, 200, { ok: true, md: "/api/report?s=" + s.id + "&format=md", json: "/api/report?s=" + s.id + "&format=json" });
  }
  if (p === "/api/report" && req.method === "GET") {
    if (!s || !s.report) return send(res, 404, { error: "no report uploaded for this session" });
    if (url.searchParams.get("format") === "md") return send(res, 200, s.report.markdown || "", { "Content-Type": "text/markdown; charset=utf-8", "Content-Disposition": "attachment; filename=bridge-lab-report.md" });
    return send(res, 200, s.report, { "Content-Disposition": "attachment; filename=bridge-lab-report.json" });
  }
  if (p === "/api/sessions" && req.method === "GET") {
    // a laptop can find the phone's session to download its report
    return send(res, 200, [...sessions.values()].filter((x) => x.report || x.alias).map((x) => ({ s: x.id, key: x.key, report: !!x.report, uploadedAt: x.report && x.report.uploadedAt })));
  }

  if (p === "/api/client-note" && req.method === "POST") {
    // the page may add a line to its backend log (e.g. "test P03 marked Pass") so the backend log reads as a story
    const b = await readJson(req);
    log.add(s ? s.key : SHARED, "note", String(b.text || "").slice(0, 300), {});
    return send(res, 200, { ok: true });
  }

  return send(res, 404, { error: "not_found" });
}

// ------------------------------------------------------------------------------------------------ server
async function handle(req, res) {
  const url = new URL(req.url, "http://lab.local");
  try {
    if (url.pathname === CFG.callbackPath && req.method === "POST") return await receiveCallback(req, res, url);
    if (url.pathname === "/healthz") return send(res, 200, { ok: true, appId: CFG.appId, key: partner.hasKey });
    if (url.pathname.startsWith("/api/")) return await api(req, res, url);
    if (req.method !== "GET" && req.method !== "HEAD") return send(res, 405, { error: "method_not_allowed" });
    if (url.pathname.startsWith("/docs/")) return serveFile(res, CFG.docsDir, decodeURIComponent(url.pathname.slice(6)));
    const rel = decodeURIComponent(url.pathname);
    if (path.extname(rel)) return serveFile(res, CFG.publicDir, rel);
    return serveFile(res, CFG.publicDir, "index.html"); // every route (deep links) gets the app
  } catch (e) {
    const status = e.status || 500;
    if (status >= 500) console.error(e);
    if (!res.headersSent) send(res, status, { error: e.code || (status >= 500 ? "internal_error" : "bad_request"), message: e.message });
  }
}

for (const port of new Set([CFG.port, CFG.altPort])) {
  http.createServer(handle).listen(port, "0.0.0.0", () => console.log(`Bridge Lab listening on :${port} (app ${CFG.appId || "NOT SEEDED"}, gateway ${CFG.base}, env ${CFG.env})`));
}
jwks.load().catch((e) => console.warn("JWKS not loaded yet: " + e.message));
