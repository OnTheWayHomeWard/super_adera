// Unit tests of Bridge Lab's logic, no browser and no stack: node --test bridge-lab/tests/unit.test.mjs (or tests/run_unit.sh).
// The fake bridge (stub-bridge.js) lives only in tests/.
import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const BM = require("../public/js/bridge.js");
const Lab = require("../public/js/lab.js");
const LabTests = require("../public/js/tests.js");
const { installStub } = require("./stub-bridge.js");
const { verifyLaunchToken, tamperedVariants } = require("../server/launch.js");
const { mask } = require("../server/partner.js");
const { Logbook } = require("../server/logbook.js");

function fakeWin() {
  return { location: { origin: "http://localhost:8095", pathname: "/", search: "", hash: "" }, navigator: { userAgent: "node" } };
}
function memStorage(broken) {
  const m = new Map();
  return { getItem: (k) => { if (broken) throw new Error("blocked"); return m.has(k) ? m.get(k) : null; }, setItem: (k, v) => { if (broken) throw new Error("blocked"); m.set(k, String(v)); }, removeItem: (k) => m.delete(k) };
}
function makeLab(win, extra) {
  const monitor = BM.createMonitor(win);
  const lab = Lab.createLab(Object.assign({ win, storage: memStorage(), fetch: async () => ({ status: 200, text: async () => "{}" }), monitor, tests: LabTests.tests,
    config: { capabilities: [{ key: "user.basic" }], entryUrl: "http://localhost:8095/?entry=1" }, BridgeMonitor: BM }, extra || {}));
  lab.openedAt = { href: "http://localhost:8095/", origin: "http://localhost:8095", pathname: "/", search: "", hash: "", query: {}, launchToken: null, ms: Date.now() };
  return lab;
}

// ------------------------------------------------------------------------------------------ message validation
test("a request in the spec's format is valid", () => {
  const r = BM.validateRequest(JSON.stringify({ adera: 1, id: "m_17", method: "requestPermission", params: { capability: "location.fine" } }));
  assert.deepEqual(r.violations, []);
});
test("bad requests are named", () => {
  assert.match(BM.validateRequest(JSON.stringify({ id: "x", method: "getPermission" })).violations.join(), /adera/);
  assert.match(BM.validateRequest(JSON.stringify({ adera: 1, id: "x".repeat(65), method: "getPermission" })).violations.join(), /64/);
  assert.match(BM.validateRequest(JSON.stringify({ adera: 1, id: "a", method: "nope" })).violations.join(), /unknown method/);
  assert.match(BM.validateRequest("{").violations.join(), /not JSON/);
  assert.match(BM.validateRequest({ adera: 1, id: "a", method: "getPermission" }).violations.join(), /JSON string/);
});
test("answers: id matching, ok/result, error codes, states", () => {
  const pending = new Map([["m_1", { method: "requestPermission" }]]);
  assert.deepEqual(BM.validateAnswer({ adera: 1, id: "m_1", ok: true, result: { state: "granted" } }, pending).violations, []);
  assert.match(BM.validateAnswer({ adera: 1, id: "m_1", ok: true, result: { state: "maybe" } }, pending).violations.join(), /state/);
  assert.match(BM.validateAnswer({ id: "m_1", ok: true, result: { state: "granted" } }, pending).violations.join(), /adera/);
  assert.match(BM.validateAnswer({ adera: 1, id: "m_1", ok: false, error: { code: "oops", message: "x" } }, pending).violations.join(), /table/);
  assert.equal(BM.validateAnswer({ adera: 1, id: "zzz", ok: true, result: {} }, pending).request, undefined);
});
test("location results: fine needs a valid location, coarse never coordinates", () => {
  assert.deepEqual(BM.validateResult("getLocation", { state: "granted", location: { latitude: 9, longitude: 38, accuracyMeters: 5, at: "2026-11-04T09:12:44Z" } }, "api"), []);
  assert.match(BM.validateResult("getLocation", { state: "granted", place: { countryCode: "ET", region: "A", city: "A", latitude: 9 } }, "api").join(), /coordinates/);
  assert.match(BM.validateResult("getLocation", { state: "granted" }, "api").join(), /without location/);
  assert.match(BM.validateResult("getLocation", { state: "denied", location: {} }, "api").join(), /reading/);
});

// ------------------------------------------------------------------------------------------ the monitor with no bridge and with a stub
test("no window.adera -> no-bridge; the verdict is 'not developed' with what to build", async () => {
  const lab = makeLab(fakeWin());
  const o = await lab.call("getPermission", ["user.basic"]);
  assert.equal(o.kind, "no-bridge");
  const v = lab.expectState(o, ["granted"], lab.monitor);
  assert.equal(v.verdict, "notdev");
  assert.match(v.mustDo, /Inject window.adera/);
});
test("a stub that follows the spec: calls resolve, the wire is captured both ways and valid", async () => {
  const win = fakeWin();
  installStub(win);
  const lab = makeLab(win);
  const o = await lab.call("getPermission", ["user.basic"]);
  assert.equal(o.kind, "resolved");
  assert.equal(o.value, "granted");
  const out = lab.monitor.entries.find((e) => e.dir === "out");
  const inn = lab.monitor.entries.find((e) => e.dir === "in");
  assert.ok(out && inn, "both directions captured");
  assert.deepEqual(out.violations, []);
  assert.deepEqual(inn.violations, []);
  assert.equal(lab.expectState(o, ["granted"], lab.monitor).verdict, "pass");
  assert.equal(lab.expectReject(await lab.call("requestPermission", ["NOT A KEY"]), "invalid_params", lab.monitor).verdict, "pass");
});
test("raw malformed messages get invalid_message / unknown_method answers, matched even without an id", async () => {
  const win = fakeWin();
  installStub(win);
  const lab = makeLab(win);
  for (const msg of ["not json", "[1]", { adera: 1, method: "getPermission", params: { capability: "user.basic" } }]) {
    const r = await lab.raw(msg, 1000);
    assert.equal(lab.expectRawError(r, "invalid_message", lab.monitor).verdict, "pass", JSON.stringify(msg));
  }
  const r = await lab.raw({ adera: 1, id: "lab_raw_x1", method: "explode", params: {} }, 1000, "lab_raw_x1");
  assert.equal(lab.expectRawError(r, "unknown_method", lab.monitor).verdict, "pass");
  assert.equal(lab.monitor.entries.filter((e) => e.unsolicited).length, 0);
});
test("an answer with the wrong id -> fail (id mismatch), and it is logged as unsolicited", async () => {
  const win = fakeWin();
  installStub(win, { broken: "wrong-id" });
  const lab = makeLab(win);
  const o = await lab.call("getPermission", ["user.basic"], 300);
  assert.equal(o.kind, "timeout");
  const v = lab.expectState(o, ["granted"], lab.monitor);
  assert.equal(v.verdict, "fail");
  assert.match(v.note, /id mismatch/);
  assert.ok(lab.monitor.entries.some((e) => e.unsolicited));
});
test("no answer at all -> not developed ('did not answer')", async () => {
  const win = fakeWin();
  installStub(win, { broken: "silent" });
  const lab = makeLab(win);
  const v = lab.expectState(await lab.call("getPermission", ["user.basic"], 200), ["granted"], lab.monitor);
  assert.equal(v.verdict, "notdev");
  assert.match(v.note, /did not answer/);
});
test("a missing method -> not developed naming it", async () => {
  const win = fakeWin();
  installStub(win, { broken: "no-getLocation" });
  const lab = makeLab(win);
  const v = lab.expectState(await lab.call("getLocation", []), ["granted"], lab.monitor);
  assert.equal(v.verdict, "notdev");
  assert.match(v.note, /getLocation/);
  assert.match(v.mustDo, /getLocation/);
});
test("a method that throws or returns no Promise is a fail", async () => {
  const win = fakeWin();
  win.adera = { getPermission() { throw new Error("boom"); }, requestPermission() { return "granted"; } };
  const lab = makeLab(win);
  assert.equal(lab.expectState(await lab.call("getPermission", ["x.y"]), ["granted"], lab.monitor).verdict, "fail");
  assert.equal(lab.expectState(await lab.call("requestPermission", ["x.y"]), ["granted"], lab.monitor).verdict, "fail");
});
test("a late injection is noticed by wrap()", () => {
  const win = fakeWin();
  const m = BM.createMonitor(win);
  assert.equal(m.wrap().injected, false);
  installStub(win);
  assert.equal(m.wrap().injected, true);
  assert.equal(m.state.wiredOut, true);
  assert.equal(m.state.wiredIn, true);
});

// ------------------------------------------------------------------------------------------ results, storage, report
test("storage that throws (blocked localStorage) does not break the lab", () => {
  const lab = makeLab(fakeWin(), { storage: memStorage(true) });
  lab.saveResult("B01", { status: "pass" });
  assert.equal(lab.result("B01").status, "pass");
});
test("suggestion and report: a notdev step puts the test in 'what the super app still needs to build'", async () => {
  const lab = makeLab(fakeWin());
  const t = LabTests.tests.find((x) => x.id === "B01");
  const v = await lab.runStep(t, 0);
  assert.equal(v.verdict, "notdev");
  assert.equal(lab.suggest(t), "notdev");
  lab.saveResult("B01", { status: "notdev", note: "nothing injected" });
  const r = lab.report();
  assert.equal(r.counts.notdev, 1);
  assert.equal(r.counts.total, 104);
  assert.ok(r.superAppTodo.find((x) => x.id === "B01" && x.mustDo[0].includes("Inject window.adera")));
  const md = lab.reportMarkdown();
  assert.match(md, /## What the super app still needs to build/);
  assert.match(md, /\| B01 \|/);
  lab.reset();
  assert.equal(lab.report().counts.notdev, 0);
});

// ------------------------------------------------------------------------------------------ the test list itself
test("104 tests, unique ids, every auto step runnable, every test has a spec link", () => {
  const ids = LabTests.tests.map((t) => t.id);
  assert.equal(ids.length, 104);
  assert.equal(new Set(ids).size, 104);
  for (const t of LabTests.tests) {
    assert.ok(t.spec, t.id + " spec");
    assert.ok(t.steps.length, t.id + " steps");
    for (const s of t.steps) if (s.kind === "auto") assert.equal(typeof s.run, "function", t.id);
  }
});
test("the README lists exactly the same tests", () => {
  const readme = fs.readFileSync(new URL("../README.md", import.meta.url), "utf8");
  const listed = [...readme.matchAll(/^\| ((?:L|B|P|S|LO|PR|N|C|D|SA|E|O)\d\d) \|/gm)].map((m) => m[1]);
  assert.deepEqual(listed.sort(), LabTests.tests.map((t) => t.id).sort());
});
test("every spec link points at an existing page and anchor in docs/guides", () => {
  const root = new URL("../../docs/guides/", import.meta.url);
  for (const t of LabTests.tests) {
    const [page, anchor] = t.spec.split("#");
    const html = fs.readFileSync(new URL(page, root), "utf8");
    if (anchor) assert.ok(html.includes('id="' + anchor + '"'), t.id + ": " + t.spec);
  }
});

// ------------------------------------------------------------------------------------------ backend: launch token verification
function makeIssuer() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const kid = "test-kid";
  const jwks = { key: async (k) => { if (k !== kid) throw new Error("unknown key id " + k); return publicKey; }, kids: () => [kid] };
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const issue = (claims) => {
    const h = enc({ alg: "RS256", typ: "JWT", kid });
    const c = enc(claims);
    return h + "." + c + "." + crypto.sign("sha256", Buffer.from(h + "." + c), privateKey).toString("base64url");
  };
  return { jwks, issue };
}
const now = Math.floor(Date.now() / 1000);
const claims = { iss: "adera", aud: "app-1", sub: "u_p_abc", env: "dev", scopes: ["user.basic"], iat: now, exp: now + 300, jti: "j1" };

test("a good token verifies; replay is flagged, not refused", async () => {
  const { jwks, issue } = makeIssuer();
  const seen = new Map();
  const r = await verifyLaunchToken(issue(claims), { jwks, appId: "app-1", env: "dev", seenJti: seen });
  assert.equal(r.valid, true, r.reason);
  const again = await verifyLaunchToken(issue(claims), { jwks, appId: "app-1", env: "dev", seenJti: seen });
  assert.equal(again.valid, true);
  assert.equal(again.replay, true);
});
test("expired, wrong app, wrong env, wrong issuer are rejected with the reason", async () => {
  const { jwks, issue } = makeIssuer();
  const tok = issue(claims);
  assert.match((await verifyLaunchToken(tok, { jwks, appId: "app-1", env: "dev", now: now + 301 })).reason, /^exp/);
  assert.match((await verifyLaunchToken(tok, { jwks, appId: "other", env: "dev" })).reason, /another app/);
  assert.match((await verifyLaunchToken(tok, { jwks, appId: "app-1", env: "prod" })).reason, /^env/);
  assert.match((await verifyLaunchToken(issue({ ...claims, iss: "evil" }), { jwks, appId: "app-1", env: "dev" })).reason, /^iss/);
});
test("all seven tampered variants are rejected by the expected check", async () => {
  const { jwks, issue } = makeIssuer();
  for (const v of tamperedVariants(issue(claims))) {
    const r = await verifyLaunchToken(v.token, { jwks, appId: v.expectAud || "app-1", env: "dev" });
    const failed = r.checks.find((c) => !c.ok && c.mandatory);
    assert.equal(r.valid, false, v.name);
    assert.equal(failed.check, v.expect, v.name + ": " + r.reason);
  }
});

// ------------------------------------------------------------------------------------------ backend: masking and the log book
test("personal data is masked in the backend log", () => {
  const m = mask({ alias: "u_p_1", phone: "+251900000001", email: "demo.abebe@example.com", fullName: "Demo Abebe Kebede", address: { city: "Addis Ababa" } });
  assert.equal(m.phone, "+2519******01");
  assert.equal(m.email, "d***@example.com");
  assert.equal(m.fullName, "D*** A*** K***");
  assert.equal(m.alias, "u_p_1");
});
test("log book: per key, capped, merge from anonymous to alias", () => {
  const log = new Logbook(3);
  const orig = console.log;
  console.log = () => {};
  try {
    for (let i = 0; i < 5; i++) log.add("anon-1", "note", "n" + i);
    log.add("unmatched", "callback", "shared");
    assert.equal(log.history("anon-1").filter((e) => e.key === "anon-1").length, 3);
    assert.ok(log.history("anon-1").some((e) => e.title === "shared"));
    log.merge("anon-1", "u_p_x");
    assert.equal(log.history("u_p_x").filter((e) => e.kind === "note").length, 3);
  } finally {
    console.log = orig;
  }
});
