// THE test list. Each test: id, group, title, goal, pre (preconditions), steps, spec (link into docs/guides), mustDo (what the
// super app must do when this is not developed). Steps:
//   H(text)                     a human instruction (no button)
//   Q(text, {yes, no})          a human answers Yes / No; each answer maps to a verdict (pass, fail, notdev, info)
//   A(label, expect, run, opts) an automatic step: "Run step" calls run(lab, ctx, test) which returns a verdict
//        opts: timeout (ms, shown as a countdown), hint (text or fn(lab, ctx) shown while it runs), mark (callbacks after
//        this step count), navigates (the page leaves; the next step resumes after it comes back)
// Text uses **bold** and `code`.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.LabTests = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const H = (text) => ({ kind: "human", text });
  const Q = (text, o) => {
    o = o || {};
    const yes = o.yes || "pass";
    const no = o.no || "fail";
    return { kind: "question", text, yes, no, expect: o.expect || (yes === "pass" ? "yes" : no === "pass" ? "no" : "either (recorded)"), notdevText: o.notdevText, mustDo: o.mustDo };
  };
  const A = (label, expect, run, o) => Object.assign({ kind: "auto", label, expect, run }, o || {});
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const DIALOG = 120000; // a human must tap: two minutes
  const SHEET = 300000;

  const SPEC = {
    methods: "app-permissions/bridge-spec.html#bridge-methods", format: "app-permissions/bridge-spec.html#bridge-format",
    origin: "app-permissions/bridge-spec.html#bridge-origin", rules: "app-permissions/bridge-spec.html#bridge-rules",
    errors: "app-permissions/bridge-spec.html#bridge-errors", mapping: "app-permissions/bridge-spec.html#mapping",
    launchPage: "app-permissions/mini-app.html#launch", launchApi: "app-permissions/super-app-api.html#ep-launch",
    sequence: "app-permissions/super-app-api.html#sequence", menu: "app-permissions/super-app-api.html#ep-menu",
    list: "app-permissions/super-app-api.html#ep-list", disconnect: "app-permissions/super-app-api.html#ep-disconnect",
    overview: "app-permissions/super-app-api.html#ep-overview", answers: "app-permissions/mini-app.html#answers",
    profile: "app-permissions/mini-app.html#profile", notify: "app-permissions/mini-app.html#notify",
    callbacks: "app-permissions/mini-app.html#callbacks", apiErrors: "app-permissions/errors.html",
    consentEvents: "partner-api/statuses-and-callbacks.html#consent-events", statusCallbacks: "partner-api/statuses-and-callbacks.html#callbacks",
    signing: "partner-api/signing.html#canonical", responses: "partner-api/signing.html#responses", authErrors: "partner-api/errors.html#auth-errors",
    synthetic: "partner-api/sandbox-and-testing.html#synthetic", idempotency: "partner-api/sending-notifications.html#idempotency",
    quota: "partner-api/limits-and-quotas.html#quota-exceeded", channels: "partner-api/sending-notifications.html#channels",
    banner: "app-banners/actions.html#mini-app", bell: "app-notifications/building-the-bell-screen.html#deeplinks",
    known: "app-permissions/known-issues.html", scenario: "app-permissions/scenario.html",
  };

  // ---------------------------------------------------------------------------------------------- shared step builders
  const expectCall = (method, args, wanted, o) => async (lab) => lab.expectState(await lab.call(method, args, (o && o.timeout) || 10000), wanted, lab.monitor, o && o.note);
  const expectReject = (method, args, code, o) => async (lab) => lab.expectReject(await lab.call(method, args, (o && o.timeout) || 10000), code, lab.monitor);
  const cb = (match, timeout) => async (lab, ctx) => lab.expectCallback(match, timeout || 30000, ctx.mark);
  const upper = (s) => String(s || "").toUpperCase();

  // precondition: the capability's state is one of `wanted`; otherwise an info verdict that says how to get there
  const pre = (cap, wanted, how) => A("Precondition: `adera.getPermission(\"" + cap + "\")` is " + wanted.map((w) => "`" + w + "`").join(" or "), wanted.join(" or "), async (lab) => {
    const o = await lab.call("getPermission", [cap]);
    const g = lab.gate(o, lab.monitor);
    if (g) return g;
    if (o.kind === "resolved" && wanted.includes(o.value)) return lab.V.pass(o.value, wanted.join(" or "));
    return lab.V.info(lab.show(o), "Precondition not met. " + (how || "") + " The next steps may then not show what they should.", wanted.join(" or "));
  });

  // the first capability (of a list) whose dialog may be shown (not_asked or denied)
  async function askable(lab, ctx, list) {
    for (const cap of list) {
      const s = await lab.state(cap);
      if (s === "not_asked" || s === "denied") { ctx.cap = cap; return cap; }
    }
    return null;
  }
  const ASKABLE = ["location.coarse", "pay.request", "user.address", "notify.email", "location.fine", "user.full_name", "user.contact", "notify.inapp", "notify.sms"];
  const pickAskable = A("Pick a permission whose dialog may be shown (`not_asked` or `denied`)", "one found", async (lab, ctx) => {
    if (!lab.monitor.state.injected) return lab.gate({ kind: "no-bridge" });
    const cap = await askable(lab, ctx, ASKABLE);
    return cap ? lab.V.pass(cap, "one found") : lab.V.info("none", "Every permission is granted or blocked. Switch one off in the menu (that makes it `denied`), then run again.", "one found");
  });
  const capLabel = (lab, ctx) => { const c = lab.capability(ctx.cap); return "**" + c.label + "** (`" + ctx.cap + "`)"; };

  async function profileCheck(lab, fields, check, expectText) {
    const r = await lab.profile(fields);
    if (r.status === 409 && r.data && r.data.error === "no_launch") return lab.V.notdev("Bridge Lab was not opened with a verified launch token, so its backend does not know who the user is.", lab.MUST.launch, SPEC.launchApi, r.data);
    if (r.status !== 200 || !r.data || r.data.status === undefined) return lab.V.fail(r.data, expectText, "The Bridge Lab backend call failed (see the Backend log).");
    return check(r.data, r.data.status, r.data.body || {});
  }

  async function snapshot(lab) {
    const out = {};
    for (const cap of lab.capabilities()) out[cap] = await lab.state(cap);
    return out;
  }

  function settingsCheck(lab, o) {
    const g = lab.gate(o, lab.monitor);
    if (g) return g;
    if (o.kind === "rejected") return lab.V.fail(lab.show(o), '{"permissions": {...}}', "Rejected with " + o.error.code);
    if (o.violations && o.violations.length) return lab.V.fail(lab.show(o), '{"permissions": {...}}', o.violations.join("; "));
    lab.store.write("lastSettings", o.value);
    return null;
  }

  async function sendNotification(lab, kind, extra) {
    const r = await lab.notify(Object.assign({ kind }, extra || {}));
    if (r.status === 409 && r.data && r.data.error === "no_launch") return { verdict: lab.V.notdev("No verified launch token: the backend does not know the user.", lab.MUST.launch, SPEC.launchApi, r.data) };
    return { r: r.data || {} };
  }

  function launchToken(lab) {
    return lab.openedAt && lab.openedAt.launchToken;
  }
  const noToken = (lab) => lab.V.notdev("The page URL has no `launch` parameter: the super app did not open Bridge Lab through the launch endpoint.", lab.MUST.launch, SPEC.launchApi, lab.openedAt ? lab.openedAt.href : "?");

  // navigation helpers (the page leaves and comes back; the step after a navigating step runs on its own on return)
  const goTo = (label, url, o) => A(label, "the page comes back", (lab, ctx, test) => {
    lab.store.write("resume", { test: test.id, at: Date.now() });
    const target = typeof url === "function" ? url(lab, ctx) : url;
    setTimeout(() => { if (target === "reload") lab.win.location.reload(); else lab.win.location.href = target; }, 300);
    return lab.V.info(target === "reload" ? "reloading..." : "navigating to " + target, "Leaving the page; the next step runs when it is back.");
  }, Object.assign({ navigates: true }, o || {}));

  function probeUrl(lab, base, expect, frame) {
    const back = lab.win.location.origin + "/?entry=1#/t/" + (frame ? "O04" : expect === "present" ? "O02" : "O03");
    return base + "/probe.html?expect=" + expect + "&s=" + encodeURIComponent(lab.sessionId) + (frame ? "&frame=1" : "&back=" + encodeURIComponent(back));
  }
  const probeResult = (which, wantPresent) => A("Read what the probe page found (it reported to the Bridge Lab backend)", wantPresent ? "window.adera present" : "no window.adera, no answer", async (lab) => {
    const r = await lab.api("GET", "/api/probe");
    const list = ((r.data && r.data.probes) || []).filter((p) => p.which === which);
    const p = list[list.length - 1];
    if (!p) return lab.V.fail("no probe report", "a report from the probe page", "The probe page did not report (did it load? see the Backend log).");
    if (wantPresent) return p.hasAdera ? lab.V.pass(p, "window.adera present") : lab.V.notdev("The bridge is missing on an origin listed in allowedOrigins.", "Inject window.adera for every origin in allowedOrigins, not only the entryUrl origin.", SPEC.origin, p);
    if (p.hasAdera) return lab.V.fail(p, "no window.adera", "The bridge was injected on a foreign origin. The spec: navigating to any other origin removes the bridge before the page loads.");
    if (p.answered) return lab.V.fail(p, "no answer", "The super app answered a message posted from a foreign origin. The spec: such a message is ignored with no answer.");
    return lab.V.pass(p, "no window.adera, no answer", p.hasChannel ? "Note: the AderaBridge channel object is visible on the foreign page, but nothing answered." : undefined);
  });

  // ---------------------------------------------------------------------------------------------- the tests
  const G = {
    L: "Launch", B: "Bridge basics", P: "Permissions", S: "Settings sheet", LO: "Location", PR: "Profile (backend)",
    N: "Notifications", C: "Callbacks", D: "Deep links", SA: "Session and app state", E: "Bridge error codes", O: "Origin rule",
  };
  const T = [];
  const t = (id, group, title, o) => T.push(Object.assign({ id, group, title }, o));

  // ============================================================================================== Launch
  t("L01", G.L, "Opened with a launch token in the URL", {
    goal: "The super app opens a mini app by calling POST /api/v1/me/apps/{app}/launch and loading the returned url, which ends in ?launch=<token>.",
    pre: ["Open Bridge Lab from the super app's catalog right before running this."], spec: SPEC.launchApi, mustDo: "Call POST /api/v1/me/apps/bridge-lab/launch and load the returned url in the WebView.",
    steps: [A("Read `launch` from the page URL and check it looks like an RS256 JWT", "a JWT with alg RS256 and a kid", async (lab) => {
      const tok = launchToken(lab);
      if (!tok) return noToken(lab);
      const parts = tok.split(".");
      let header = null;
      try { header = JSON.parse(atob(parts[0].replace(/-/g, "+").replace(/_/g, "/"))); } catch { /* not JSON */ }
      if (parts.length !== 3 || !header) return lab.V.fail(tok.slice(0, 40) + "...", "a JWT", "Not a JWT.");
      return header.alg === "RS256" && header.kid ? lab.V.pass({ header, length: tok.length }, "a JWT with alg RS256 and a kid") : lab.V.fail(header, "alg RS256 and a kid");
    })],
  });
  t("L02", G.L, "The registered entry URL is kept (path and query)", {
    goal: "The launch answer's url is the registered entryUrl with the token appended (& when the entryUrl already has a query). The super app must load it as is, not rebuild it.",
    pre: ["Opened from the catalog (not from a banner route)."], spec: SPEC.launchApi, mustDo: "Load the launch answer's url exactly as returned (entryUrl + ?launch= or &launch=).",
    steps: [A("Compare the page URL with the registered entryUrl", "entryUrl's path and query kept, launch appended", async (lab) => {
      const tok = launchToken(lab);
      if (!tok) return noToken(lab);
      const entry = new URL(lab.config.entryUrl);
      const here = lab.openedAt;
      const problems = [];
      if (here.origin !== entry.origin) problems.push("origin " + here.origin + " is not the entryUrl origin " + entry.origin);
      if (here.pathname !== entry.pathname) problems.push("path " + here.pathname + " is not " + entry.pathname + (here.pathname.indexOf("/deeplink/") === 0 ? " (opened at a deep link: use D02 instead)" : ""));
      for (const [k, v] of entry.searchParams) if (here.query[k] !== v) problems.push("query " + k + "=" + v + " was dropped");
      const expected = entry.search ? entry.search + "&launch=" : "?launch=";
      if (!here.search.startsWith(expected)) problems.push("token not appended after the entryUrl's query (want " + expected + "...)");
      const actual = { entryUrl: lab.config.entryUrl, opened: here.origin + here.pathname + here.search.replace(/launch=[^&]+/, "launch=<token>") };
      return problems.length ? lab.V.fail(actual, "entryUrl + &launch=<token>", problems.join("; ")) : lab.V.pass(actual, "entryUrl + &launch=<token>");
    })],
  });
  t("L03", G.L, "The backend verifies the token against the JWKS", {
    goal: "The mini app's backend verifies the RS256 signature with Adera's public key (kid from /.well-known/jwks.json) and checks iss, aud, exp and env.",
    pre: ["Opened from the super app (the token is verified automatically when the page loads)."], spec: SPEC.launchPage, mustDo: "Issue launch tokens with POST .../launch (already built in the backend); load the url with the token.",
    steps: [A("Show the result of the verification done at page load", "valid: every mandatory check passes", async (lab) => {
      if (!launchToken(lab)) return noToken(lab);
      const r = await lab.api("POST", "/api/launch/verify", { token: launchToken(lab), probe: true, label: "L03" });
      const d = r.data || {};
      const act = { valid: d.valid, reason: d.reason, claims: d.claims, checks: (d.checks || []).map((c) => (c.ok ? "ok " : c.mandatory ? "FAIL " : "warn ") + c.check + ": " + c.detail) };
      if (d.valid) return lab.V.pass(act, "valid");
      if (d.checks && d.checks.find((c) => c.check === "exp" && !c.ok)) return lab.V.info(act, "Valid when issued but already expired (tokens live 5 minutes): reopen Bridge Lab and run again.", "valid");
      return lab.V.fail(act, "valid", d.reason);
    })],
  });
  t("L04", G.L, "The token is fresh: 5-minute life, right environment", {
    goal: "exp - iat = 300 s, iat is the moment of opening, env is this deployment's (dev here).", spec: SPEC.launchPage, mustDo: "Ask for a new launch token on every opening; never reuse an old one.",
    steps: [A("Check iat, exp and env of the verified token", "exp - iat = 300, iat within 2 minutes of opening, env = " + "deployment", async (lab) => {
      if (!launchToken(lab)) return noToken(lab);
      const c = (lab.launch && lab.launch.claims) || {};
      const openedS = Math.floor(lab.openedAt.ms / 1000);
      const probs = [];
      if (c.exp - c.iat !== 300) probs.push("exp - iat = " + (c.exp - c.iat));
      if (Math.abs(openedS - c.iat) > 120) probs.push("issued " + (openedS - c.iat) + " s before the page opened (an old token, or a phone clock far off)");
      if (c.env !== lab.config.env) probs.push("env " + c.env + " (deployment " + lab.config.env + ")");
      const act = { iat: c.iat, exp: c.exp, env: c.env, issuedSecondsBeforeOpening: openedS - c.iat };
      return probs.length ? lab.V.fail(act, "300 s, fresh, env " + lab.config.env, probs.join("; ")) : lab.V.pass(act, "300 s, fresh, env " + lab.config.env);
    })],
  });
  t("L05", G.L, "No permission dialog appears when the app opens", {
    goal: "Opening a mini app is POST .../launch only. Permissions are asked at the moment of need, never at launch.", spec: SPEC.rules, mustDo: "Show no dialog at launch.",
    steps: [H("Close Bridge Lab and open it again from the catalog. Watch the screen while it opens."), Q("Did any permission dialog appear while Bridge Lab opened?", { yes: "fail", no: "pass", expect: "no" })],
  });
  t("L06", G.L, "The scopes in the token match the permission states", {
    goal: "scopes = the capabilities the user allowed at the moment of opening. A hint only, but right after opening they must match what getPermission says.", spec: SPEC.launchPage,
    mustDo: "Nothing extra: the launch endpoint fills scopes; getPermission must return the same states.",
    steps: [A("Compare the token's scopes with getPermission for every capability", "the same set", async (lab) => {
      if (!launchToken(lab)) return noToken(lab);
      const scopes = ((lab.launch && lab.launch.claims && lab.launch.claims.scopes) || []).slice().sort();
      const o = await lab.call("getPermission", ["user.basic"]);
      const g = lab.gate(o, lab.monitor);
      if (g) return g;
      const snap = await snapshot(lab);
      const granted = Object.keys(snap).filter((k) => snap[k] === "granted").sort();
      const act = { scopes, grantedNow: granted };
      return JSON.stringify(scopes) === JSON.stringify(granted) ? lab.V.pass(act, "the same set") : lab.V.fail(act, "the same set", "Differs. If you changed a permission after opening, reopen Bridge Lab and run again.");
    })],
  });
  t("L07", G.L, "Tampered and foreign tokens are rejected", {
    goal: "The backend must refuse a changed signature, changed claims, alg none, an HMAC alg, an unknown kid, garbage, and a token for another app.", spec: SPEC.launchPage,
    mustDo: "Nothing (backend check); needs a real token from the super app as the base.",
    steps: [A("Send 7 modified copies of the real token to the Bridge Lab backend", "all 7 rejected by the right check", async (lab) => {
      if (!launchToken(lab)) return noToken(lab);
      const r = await lab.api("POST", "/api/launch/tampered", { token: launchToken(lab) });
      const probes = (r.data && r.data.probes) || [];
      const act = probes.map((p) => (p.ok ? "rejected (" + p.failingCheck + ")" : p.valid ? "ACCEPTED" : "rejected by " + p.failingCheck + ", expected " + p.expectFailingCheck) + ": " + p.name);
      return probes.length === 7 && probes.every((p) => p.ok) ? lab.V.pass(act, "all 7 rejected") : lab.V.fail(act, "all 7 rejected");
    })],
  });
  t("L08", G.L, "An expired token is rejected", {
    goal: "Tokens live 5 minutes; a mini app backend must refuse an expired one and ask for a new launch.", spec: SPEC.launchPage, mustDo: "Nothing (backend check).",
    steps: [H("Leave Bridge Lab open for **5 minutes** after opening it (the step tells you the time left)."),
      A("Verify the page's token again", "rejected: expired", async (lab) => {
        if (!launchToken(lab)) return noToken(lab);
        const c = (lab.launch && lab.launch.claims) || {};
        const left = c.exp - Math.floor(Date.now() / 1000);
        if (left > 0) return lab.V.info(left + " s left", "Not expired yet: run this step again in " + left + " s.", "rejected: expired");
        const r = await lab.api("POST", "/api/launch/verify", { token: launchToken(lab), probe: true, label: "L08 expired" });
        const d = r.data || {};
        return !d.valid && /^exp/.test(d.reason || "") ? lab.V.pass(d.reason, "rejected: expired") : lab.V.fail({ valid: d.valid, reason: d.reason }, "rejected: expired");
      })],
  });
  t("L09", G.L, "A replayed token (same jti) is noticed", {
    goal: "jti is a random id; a backend may refuse a replay. Bridge Lab flags it (a warning, so a reload still works).", spec: SPEC.launchPage, mustDo: "Nothing (backend check).",
    steps: [A("Verify the page's token a second time", "flagged as a replay", async (lab) => {
      if (!launchToken(lab)) return noToken(lab);
      const r = await lab.api("POST", "/api/launch/verify", { token: launchToken(lab), label: "L09 replay" });
      const d = r.data || {};
      return d.replay ? lab.V.pass({ replay: true, jti: d.claims && d.claims.jti }, "flagged as a replay") : lab.V.fail({ replay: d.replay, reason: d.reason }, "flagged as a replay");
    })],
  });
  t("L10", G.L, "Every opening gets a new token", {
    goal: "Each opening calls launch again: a new jti and a new iat.", spec: SPEC.launchApi, mustDo: "Call POST .../launch on every opening; never cache the url with its token.",
    steps: [H("Close Bridge Lab (back to the super app), open it again from the catalog, come back to this test."),
      A("Compare this opening's token with the previous opening's", "a different jti, a later iat", async (lab) => {
        if (!launchToken(lab)) return noToken(lab);
        const list = lab.store.read("launches") || [];
        if (list.length < 2) return lab.V.info(list, "Only one opening recorded so far: close and reopen Bridge Lab, then run again.", "two openings");
        const [prev, cur] = list.slice(-2);
        const act = { previous: prev, current: cur };
        return prev.jti !== cur.jti && cur.iat > prev.iat ? lab.V.pass(act, "a different jti, a later iat") : lab.V.fail(act, "a different jti, a later iat", prev.jti === cur.jti ? "The same token was loaded again (the super app reused the url)." : "");
      })],
  });
  t("L11", G.L, "The page receives no session token, user id or scopes from the super app", {
    goal: "The super app keeps the user's session to itself; the page gets only the launch token in the URL.", spec: SPEC.origin,
    mustDo: "Never put the session token, the user id or the scopes into the page (no globals, cookies or extra URL parameters).",
    steps: [A("Look for anything session-like in window.adera, cookies, the URL and storage", "nothing found", async (lab) => {
      const w = lab.win;
      const found = [];
      const a = w.adera;
      if (a) for (const k of Object.keys(a)) if (!["requestPermission", "getPermission", "openPermissionSettings", "getLocation", "_receive"].includes(k)) found.push("window.adera." + k + " = " + String(a[k]).slice(0, 60));
      if (w.document && w.document.cookie) found.push("cookies: " + w.document.cookie.slice(0, 80));
      for (const k of Object.keys(lab.openedAt.query)) if (!["entry", "launch", "promo", "ref"].includes(k)) found.push("URL parameter " + k);
      try { for (let i = 0; i < w.localStorage.length; i++) { const k = w.localStorage.key(i); if (k.indexOf("bridgeLab") !== 0) found.push("localStorage " + k); } } catch { /* none */ }
      for (const k of ["aderaSession", "aderaToken", "AderaSession", "sessionToken", "userId", "aderaUser"]) if (w[k] !== undefined) found.push("window." + k);
      return found.length ? lab.V.fail(found, "nothing found", "Check each item: the spec says the mini app never receives the session token, the user's id or the launch scopes through the bridge.") : lab.V.pass("nothing found", "nothing found");
    })],
  });
  t("L12", G.L, "Opening Bridge Lab while signed out", {
    goal: "A launch needs the user's session: a signed-out user signs in first, then the app opens with a token.", spec: SPEC.banner,
    mustDo: "Not specified in detail: when a mini app is opened while signed out (from a link or banner), sign the user in first, then launch.",
    steps: [H("Sign out of the super app. Open Bridge Lab from wherever the signed-out app offers it (catalog, banner, link)."),
      Q("Did the super app ask you to sign in first and then open Bridge Lab (Bridge Lab shows your id at the top)?", { yes: "pass", no: "notdev", notdevText: "Opening a mini app while signed out does not lead through sign-in to a launch." })],
  });

  // ============================================================================================== Bridge basics
  t("B01", G.B, "window.adera is injected", {
    goal: "The super app injects window.adera into the page of the registered origin.", spec: SPEC.methods, mustDo: null,
    steps: [A("Look for window.adera", "an object", async (lab) => {
      lab.monitor.wrap();
      const a = lab.win.adera;
      if (!a) return lab.gate({ kind: "no-bridge" });
      return lab.V.pass({ type: typeof a, keys: Object.keys(a), injectedAt: lab.monitor.state.injectedAt }, "an object");
    })],
  });
  t("B02", G.B, "The four methods exist and return Promises", {
    goal: "requestPermission, getPermission, openPermissionSettings and getLocation, each returning a Promise.", spec: SPEC.methods,
    steps: [A("Check the four methods and call getPermission(\"user.basic\")", "4 functions; the call returns a Promise that settles", async (lab) => {
      lab.monitor.wrap();
      const a = lab.win.adera;
      if (!a) return lab.gate({ kind: "no-bridge" });
      const missing = ["requestPermission", "getPermission", "openPermissionSettings", "getLocation"].filter((m) => typeof a[m] !== "function");
      if (missing.length) return lab.V.notdev("Missing: " + missing.join(", "), missing.map((m) => lab.MUST[m]).join(" "), SPEC.methods, { missing });
      const o = await lab.call("getPermission", ["user.basic"]);
      return lab.gate(o, lab.monitor) || lab.V.pass(lab.show(o), "4 functions; a Promise that settles");
    })],
  });
  t("B03", G.B, "Transport: the AderaBridge channel and window.adera._receive", {
    goal: "In the Flutter WebView the page posts JSON strings to the JavaScript channel AderaBridge and the super app answers through window.adera._receive(json).", spec: SPEC.format,
    mustDo: "Register a JavaScript channel named AderaBridge and answer by calling window.adera._receive(json).",
    steps: [A("Detect the transport", "AderaBridge.postMessage and window.adera._receive", async (lab) => {
      const st = lab.monitor.wrap();
      const w = lab.win;
      const act = { transport: st.transport, AderaBridge: !!w.AderaBridge, postMessage: !!(w.AderaBridge && w.AderaBridge.postMessage), _receive: !!(w.adera && typeof w.adera._receive === "function"), outgoingCaptured: st.wiredOut, answersCaptured: st.wiredIn };
      if (!st.injected) return lab.gate({ kind: "no-bridge" });
      if (act.postMessage && act._receive) return lab.V.pass(act, "channel + _receive");
      if (w.parent !== w) return lab.V.info(act, "Running in an iframe: the web transport (postMessage to the parent) applies; see B11.");
      return lab.V.fail(act, "channel + _receive", "window.adera exists but the documented transport is not visible (the page cannot post raw messages, see the E tests).");
    })],
  });
  async function oneCall(lab) {
    const before = lab.monitor.entries.length;
    const o = await lab.call("getPermission", ["user.basic"]);
    return { o, entries: lab.monitor.entries.slice(before) };
  }
  t("B04", G.B, "Request messages follow the format", {
    goal: 'A request is {"adera":1,"id":"<at most 64>","method":"...","params":{...}} posted as a JSON string.', spec: SPEC.format,
    steps: [A("Call getPermission(\"user.basic\") and inspect the message window.adera posted", "a valid request", async (lab) => {
      const { o, entries } = await oneCall(lab);
      const g = lab.gate(o, lab.monitor);
      if (g && o.kind !== "timeout") return g;
      const out = entries.find((e) => e.dir === "out");
      if (!out) return lab.V.info({ outgoingCaptured: lab.monitor.state.wiredOut }, "The request could not be captured (the channel could not be wrapped, or window.adera does not use AderaBridge).", "a valid request");
      return out.violations.length ? lab.V.fail(out.payload, "a valid request", out.violations.join("; ")) : lab.V.pass(out.payload, "a valid request");
    })],
  });
  t("B05", G.B, "Answer messages follow the format", {
    goal: 'An answer repeats the id and has "ok": true with "result", or "ok": false with "error": {code, message}; "adera": 1.', spec: SPEC.format, mustDo: null,
    steps: [A("Call getPermission(\"user.basic\") and inspect the answer passed to _receive", "a valid answer with the same id", async (lab) => {
      const { o, entries } = await oneCall(lab);
      const g = lab.gate(o, lab.monitor);
      const inn = entries.find((e) => e.dir === "in");
      if (g && !inn) return g;
      if (!inn) return lab.V.info({ answersCaptured: lab.monitor.state.wiredIn }, "The answer could not be captured (_receive could not be wrapped).", "a valid answer");
      return inn.violations.length ? lab.V.fail(inn.payload, "a valid answer", inn.violations.join("; ")) : lab.V.pass({ answer: inn.payload, roundTripMs: inn.ms }, "a valid answer");
    })],
  });
  t("B06", G.B, "Parallel calls are matched by id", {
    goal: "Several calls in flight at once each get their own answer (the id ties them together).", spec: SPEC.format,
    steps: [A('getPermission for "user.basic", "notify.inapp" and "camera.photo" at the same time', 'each its own answer; camera.photo -> "not_available"', async (lab) => {
      const caps = ["user.basic", "notify.inapp", "camera.photo"];
      const os = await Promise.all(caps.map((c) => lab.call("getPermission", [c])));
      for (const o of os) { const g = lab.gate(o, lab.monitor); if (g) return g; }
      const act = {};
      caps.forEach((c, i) => (act[c] = lab.show(os[i])));
      const bad = os.filter((o) => o.kind !== "resolved" || (o.violations && o.violations.length));
      if (bad.length) return lab.V.fail(act, "three valid answers");
      return os[2].value === "not_available" ? lab.V.pass(act, "three answers, camera.photo not_available") : lab.V.fail(act, 'camera.photo -> "not_available"', "Answers may be mixed up between calls (or camera.photo is not reported as not_available).");
    })],
  });
  t("B07", G.B, "The bridge is still there after a reload", {
    goal: "Reloading the page (same origin) gets window.adera again.", spec: SPEC.origin, mustDo: "Inject window.adera on every page load of an allowed origin, including reloads.",
    steps: [goTo("Reload the page", "reload"), A("After the reload: look for window.adera", "present", async (lab) => (lab.win.adera ? lab.V.pass(Object.keys(lab.win.adera), "present") : lab.gate({ kind: "no-bridge" })), { resume: true })],
  });
  t("B08", G.B, "The bridge is still there after same-origin navigation", {
    goal: "Navigating to another page of the same origin keeps the bridge.", spec: SPEC.origin, mustDo: "Inject window.adera on every page of an allowed origin, not only the first one.",
    steps: [goTo("Navigate to /lab/second-page (same origin)", (lab) => lab.win.location.origin + "/lab/second-page#/t/B08"),
      A("On the second page: look for window.adera", "present on /lab/second-page", async (lab) => {
        if (lab.win.location.pathname !== "/lab/second-page") return lab.V.info(lab.win.location.pathname, "Not on the second page: run the previous step first.");
        return lab.win.adera ? lab.V.pass({ path: lab.win.location.pathname, keys: Object.keys(lab.win.adera) }, "present") : lab.gate({ kind: "no-bridge" });
      }, { resume: true }),
      goTo("Back to the start page", (lab) => lab.win.location.origin + "/?entry=1#/t/B08")],
  });
  t("B09", G.B, "Unsolicited messages from the super app are logged and checked", {
    goal: "The spec defines answers only: every message from the super app answers a request. Anything else is listed here.", spec: SPEC.format,
    steps: [A("List super-app messages that answered no request", "none", async (lab) => {
      const stray = lab.monitor.entries.filter((e) => e.dir === "in" && e.unsolicited);
      if (!lab.monitor.state.injected) return lab.gate({ kind: "no-bridge" });
      return stray.length ? lab.V.fail(stray.map((e) => e.payload), "none", "Messages with an id no request had (or no id).") : lab.V.pass("none so far (" + lab.monitor.entries.filter((e) => e.dir === "in").length + " answers seen)", "none");
    })],
  });
  t("B10", G.B, "getPermission never shows a dialog", {
    goal: "getPermission only reads; it never opens a dialog, for any capability.", spec: SPEC.methods,
    steps: [A("getPermission for every capability of the app", "a state for each, no dialog", async (lab) => {
      const o = await lab.call("getPermission", ["user.basic"]);
      const g = lab.gate(o, lab.monitor);
      if (g) return g;
      const out = {};
      const bad = [];
      for (const cap of lab.capabilities()) {
        const r = await lab.call("getPermission", [cap], 15000);
        out[cap] = r.kind === "resolved" ? r.value : lab.show(r);
        if (r.kind !== "resolved" || (r.violations && r.violations.length)) bad.push(cap);
      }
      return bad.length ? lab.V.fail(out, "a valid state each", "Bad answers for " + bad.join(", ")) : lab.V.pass(out, "a valid state each");
    }, { timeout: 60000 }), Q("Did any dialog appear during that step?", { yes: "fail", no: "pass", expect: "no" })],
  });
  t("B11", G.B, "Web-hosted super app (iframe + postMessage)", {
    goal: "In a web-hosted super app the page talks to window.parent with postMessage. Nothing says who provides window.adera inside a cross-origin iframe.", spec: SPEC.format,
    mustDo: "Not specified yet: for a web-hosted super app, define how window.adera gets into the mini app's iframe (for example a small Adera script the page includes) and the super app's origin the page posts to.",
    steps: [A("Detect whether the page runs in an iframe", "native WebView (not an iframe), or an iframe with window.adera", async (lab) => {
      const w = lab.win;
      if (w.parent === w) return lab.V.skip("Not in an iframe (native WebView or a plain browser tab): not applicable.", { iframe: false });
      return w.adera ? lab.V.pass({ iframe: true, adera: true }, "window.adera in the iframe") : lab.V.notdev("In an iframe without window.adera.", undefined, undefined, { iframe: true, adera: false });
    })],
  });

  // ============================================================================================== Permissions
  t("P01", G.P, "user.basic is granted with no dialog", {
    goal: "The pseudonymous id needs no dialog: requestPermission(\"user.basic\") resolves \"granted\" at once (unless the user disconnected).", spec: SPEC.methods, mustDo: null,
    steps: [A('adera.requestPermission("user.basic")', '"granted"', expectCall("requestPermission", ["user.basic"], ["granted"], { note: "denied means the user disconnected: reopen the app (launch reconnects user.basic)." })),
      Q("Did a dialog appear?", { yes: "fail", no: "pass", expect: "no" })],
  });
  t("P02", G.P, "getPermission says not_asked before the first ask", {
    goal: "A capability never asked is not_asked.", spec: SPEC.answers,
    steps: [A("getPermission for every capability; at least one never-asked one is not_asked", "some not_asked (on a fresh user)", async (lab) => {
      const o = await lab.call("getPermission", ["user.basic"]);
      const g = lab.gate(o, lab.monitor);
      if (g) return g;
      const snap = await snapshot(lab);
      const na = Object.keys(snap).filter((k) => snap[k] === "not_asked");
      return na.length ? lab.V.pass(snap, "some not_asked") : lab.V.info(snap, "Every permission has been answered already; NOT_ASKED never comes back for a user. Use a fresh test user to see it.", "some not_asked");
    })],
  });
  t("P03", G.P, "Ask, Allow -> granted (user.full_name)", {
    goal: "The full dialog round: request -> dialog with Adera's text -> Allow -> answer -> \"granted\"; the backend then reads the name and gets a callback.", spec: SPEC.sequence, mustDo: null,
    pre: ["`user.full_name` not granted yet (switch it off in the menu if needed)."],
    steps: [pre("user.full_name", ["not_asked", "denied"], "Switch **Your name** off in the menu first."),
      A('adera.requestPermission("user.full_name")', '"granted"', expectCall("requestPermission", ["user.full_name"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, mark: true, hint: "A dialog must appear. Tap **Allow**." }),
      Q("Did a dialog with Allow and Deny appear?", { yes: "pass", no: "fail" }),
      A("Backend: GET /v1/users/{alias}/profile", "200 with user.full_name granted and fullName", (lab) => profileCheck(lab, null, (d, st, b) => (st === 200 && (b.granted || []).includes("user.full_name") && b.fullName ? lab.V.pass(b, "fullName present") : lab.V.fail(b, "fullName present")), "200")),
      A("Callback user.consent_changed user.full_name GRANTED", "signed callback within 30 s", cb({ event: "user.consent_changed", capability: "user.full_name", state: "GRANTED" }))],
  });
  t("P04", G.P, "The dialog shows Adera's approved label and purpose verbatim", {
    goal: "The dialog shows only prompt.label and prompt.purpose from the server, as plain text; the mini app cannot change or style it.", spec: SPEC.rules,
    mustDo: "Show the dialog with prompt.label and prompt.purpose exactly as the server sent them, plain text, nothing from the mini app.",
    steps: [A("The text the dialog must show for pay.request", "label and purpose", async (lab) => { const c = lab.capability("pay.request"); return lab.V.info({ label: c.label, purpose: c.purpose }, "Keep this in view; compare it with the dialog."); }),
      pre("pay.request", ["not_asked", "denied"], "Switch **Payments** off in the menu first."),
      A('adera.requestPermission("pay.request")', "a state", expectCall("requestPermission", ["pay.request"], ["granted", "denied"], { timeout: DIALOG }), { timeout: DIALOG, hint: "Compare the dialog with the text above, then tap **Allow** or **Deny**." }),
      Q("Did the dialog show exactly that label and purpose, as plain text, with nothing added by Bridge Lab?")],
  });
  t("P05", G.P, "Ask, Deny -> denied (user.contact)", {
    goal: "The first no sets DENIED (count 1): the page gets \"denied\", the backend gets 403 consent_required DENIED, and a callback.", spec: SPEC.answers, mustDo: null,
    steps: [pre("user.contact", ["not_asked"], "This needs a user that was never asked for user.contact."),
      A('adera.requestPermission("user.contact")', '"denied"', expectCall("requestPermission", ["user.contact"], ["denied"], { timeout: DIALOG }), { timeout: DIALOG, mark: true, hint: "A dialog must appear. Tap **Deny**." }),
      A('adera.getPermission("user.contact")', '"denied"', expectCall("getPermission", ["user.contact"], ["denied"])),
      A("Backend: profile ?fields=user.contact", "403 consent_required, state DENIED", (lab) => profileCheck(lab, ["user.contact"], (d, st, b) => (st === 403 && b.error === "consent_required" && b.state === "DENIED" ? lab.V.pass(b, "403 DENIED") : lab.V.fail({ status: st, body: b }, "403 consent_required DENIED")), "403")),
      A("Callback user.consent_changed user.contact DENIED", "signed callback", cb({ event: "user.consent_changed", capability: "user.contact", state: "DENIED" }))],
  });
  t("P06", G.P, "Ask again after a denial, Deny -> blocked", {
    goal: "After one no the dialog may be shown once more; the second no sets BLOCKED.", spec: SPEC.answers, mustDo: null,
    steps: [pre("user.contact", ["denied"], "Run P05 first."),
      A('adera.requestPermission("user.contact")', '"blocked"', expectCall("requestPermission", ["user.contact"], ["blocked"], { timeout: DIALOG }), { timeout: DIALOG, mark: true, hint: "The dialog must appear again. Tap **Deny**." }),
      Q("Did the dialog appear this second time?"),
      A("Callback user.consent_changed user.contact BLOCKED", "signed callback", cb({ event: "user.consent_changed", capability: "user.contact", state: "BLOCKED" }))],
  });
  t("P07", G.P, "blocked answers at once with no dialog", {
    goal: "A blocked permission resolves \"blocked\" with no dialog; only the menu can unblock it.", spec: SPEC.answers, mustDo: null,
    steps: [pre("user.contact", ["blocked"], "Run P05 and P06 first."),
      A('adera.requestPermission("user.contact")', '"blocked" within 3 s', async (lab) => {
        const o = await lab.call("requestPermission", ["user.contact"]);
        const v = lab.expectState(o, ["blocked"], lab.monitor);
        if (v.verdict === "pass" && o.ms > 3000) return lab.V.fail(lab.show(o), '"blocked" at once', "Took " + o.ms + " ms: was a dialog shown?");
        return v;
      }), Q("Did a dialog appear?", { yes: "fail", no: "pass", expect: "no" })],
  });
  t("P08", G.P, "The server decides: a switch changed in the menu is seen at once", {
    goal: "requestPermission always asks the server first (never a cached state).", spec: SPEC.rules, mustDo: "Never answer from a cached state: requestPermission always calls the server first.",
    steps: [pre("user.contact", ["blocked", "denied"], "Block or deny user.contact first (P05, P06)."),
      H("Leave Bridge Lab open. In the super app's menu for Bridge Lab -> **Permissions**, switch **Your phone number and email** on. Close the sheet."),
      A('adera.requestPermission("user.contact")', '"granted" with no dialog', expectCall("requestPermission", ["user.contact"], ["granted"])),
      A("Callback user.consent_changed user.contact GRANTED", "signed callback", cb({ event: "user.consent_changed", capability: "user.contact", state: "GRANTED" }, 20000))],
  });
  t("P09", G.P, "not_available for a capability the app was not granted", {
    goal: "A capability staff did not grant never gets a dialog: requestPermission and getPermission resolve \"not_available\".", spec: SPEC.methods,
    steps: [A('adera.requestPermission("camera.photo")', '"not_available"', expectCall("requestPermission", ["camera.photo"], ["not_available"])),
      A('adera.getPermission("camera.photo")', '"not_available"', expectCall("getPermission", ["camera.photo"], ["not_available"])),
      Q("Did a dialog appear?", { yes: "fail", no: "pass", expect: "no" })],
  });
  t("P10", G.P, "Staff remove a grant while the app is open", {
    goal: "Gate 1 is checked on every call: a capability staff remove becomes not_available at once (within about 15 s).", spec: SPEC.list,
    steps: [H("Ask staff: backoffice -> Apps -> **Bridge Lab** -> Capabilities: set **user.address** to DENIED. Wait 20 seconds."),
      A('adera.requestPermission("user.address")', '"not_available"', expectCall("requestPermission", ["user.address"], ["not_available"])),
      A('adera.getPermission("user.address")', '"not_available"', expectCall("getPermission", ["user.address"], ["not_available"])),
      H("Staff: set **user.address** back to GRANTED (or run `python3 -I bridge-lab/seed.py` again).")],
  });
  t("P11", G.P, "The backend sees the same consent", {
    goal: "What the page sees and what the backend's profile call returns agree (the same three gates).", spec: SPEC.profile,
    steps: [A("Compare getPermission of the user.* capabilities with the profile's granted list", "the same set", async (lab) => {
      const o = await lab.call("getPermission", ["user.basic"]);
      const g = lab.gate(o, lab.monitor);
      if (g) return g;
      const page = [];
      for (const cap of ["user.basic", "user.full_name", "user.contact", "user.address"]) if ((await lab.state(cap)) === "granted") page.push(cap);
      return profileCheck(lab, null, (d, st, b) => {
        const back = (b.granted || []).slice().sort();
        const act = { page: page.sort(), backend: back, status: st };
        if (st === 403 && page.length === 0) return lab.V.pass(act, "the same set");
        return JSON.stringify(page) === JSON.stringify(back) ? lab.V.pass(act, "the same set") : lab.V.fail(act, "the same set");
      }, "the same set");
    })],
  });
  t("P12", G.P, "One permission per call (no batch)", {
    goal: "There is no batch call: an array is not a capability.", spec: SPEC.rules,
    steps: [A('adera.requestPermission(["location.fine", "user.contact"])', "rejects invalid_params", expectReject("requestPermission", [["location.fine", "user.contact"]], "invalid_params")),
      A("No extra batch method on window.adera", "only the four methods", async (lab) => {
        if (!lab.win.adera) return lab.gate({ kind: "no-bridge" });
        const extra = Object.keys(lab.win.adera).filter((k) => !["requestPermission", "getPermission", "openPermissionSettings", "getLocation", "_receive"].includes(k) && typeof lab.win.adera[k] === "function");
        return extra.length ? lab.V.info(extra, "Extra functions (not in the spec).", "only the four methods") : lab.V.pass("only the four (+ _receive)", "only the four methods");
      })],
  });

  // ============================================================================================== Settings sheet
  t("S01", G.S, "openPermissionSettings() opens the sheet and resolves on close", {
    goal: "The page can open its own permission sheet; the Promise resolves with the states when the sheet closes.", spec: SPEC.methods, mustDo: null,
    steps: [A("adera.openPermissionSettings()", '{"permissions": {...}} after closing', async (lab) => {
      const o = await lab.call("openPermissionSettings", [], SHEET);
      return settingsCheck(lab, o) || lab.V.pass(lab.show(o), '{"permissions": {...}}');
    }, { timeout: SHEET, hint: "The permission sheet must open. Change nothing; close it." }),
      Q("Did a sheet with one switch per permission open?")],
  });
  t("S02", G.S, "The sheet lists exactly the app's granted capabilities", {
    goal: "The sheet lists only what staff granted the app (all 10 for Bridge Lab), with Adera's labels.", spec: SPEC.menu,
    steps: [A("adera.openPermissionSettings() and compare its keys with the app's capabilities", "the same 10 capabilities", async (lab) => {
      const o = await lab.call("openPermissionSettings", [], SHEET);
      const bad = settingsCheck(lab, o);
      if (bad) return bad;
      const keys = Object.keys(o.value.permissions).sort();
      const want = lab.capabilities().slice().sort();
      return JSON.stringify(keys) === JSON.stringify(want) ? lab.V.pass(o.value.permissions, want.join(", ")) : lab.V.fail({ got: keys, want }, "the app's capabilities");
    }, { timeout: SHEET, hint: "Look at the sheet, then close it." }),
      Q("Did the sheet show Adera's labels (for example \"Your precise location\", \"Your name\"), not text from Bridge Lab?")],
  });
  t("S03", G.S, "Unblock only from the menu", {
    goal: "blocked cannot be asked again; the menu switch is the only way out, and it sets GRANTED with the count back to 0.", spec: SPEC.menu, mustDo: null,
    steps: [A("Find a blocked permission", "one found", async (lab, ctx) => {
      if (!lab.win.adera) return lab.gate({ kind: "no-bridge" });
      const snap = await snapshot(lab);
      ctx.cap = Object.keys(snap).find((k) => snap[k] === "blocked");
      return ctx.cap ? lab.V.pass(ctx.cap, "one found") : lab.V.info(snap, "Nothing is blocked: run P05 and P06 (or deny any dialog twice) first.", "one found");
    }),
      A("requestPermission(<it>)", '"blocked" (no dialog)', async (lab, ctx) => lab.expectState(await lab.call("requestPermission", [ctx.cap]), ["blocked"], lab.monitor)),
      A("adera.openPermissionSettings()", "the switched one is granted", async (lab, ctx) => {
        const o = await lab.call("openPermissionSettings", [], SHEET);
        const bad = settingsCheck(lab, o);
        if (bad) return bad;
        return o.value.permissions[ctx.cap] === "granted" ? lab.V.pass(o.value.permissions, ctx.cap + ": granted") : lab.V.fail(o.value.permissions, ctx.cap + ": granted");
      }, { timeout: SHEET, mark: true, hint: (lab, ctx) => "In the sheet, switch " + capLabel(lab, ctx) + " **on**, then close the sheet." }),
      A("requestPermission(<it>) again", '"granted", no dialog', async (lab, ctx) => lab.expectState(await lab.call("requestPermission", [ctx.cap]), ["granted"], lab.monitor)),
      A("Callback user.consent_changed GRANTED", "signed callback", async (lab, ctx) => lab.expectCallback({ event: "user.consent_changed", capability: ctx.cap, state: "GRANTED" }, 30000, ctx.mark))],
  });
  t("S04", G.S, "Switch off in the sheet -> denied, and the dialog may be shown again", {
    goal: "Switching off sets DENIED with count 0, so the mini app may ask again later (the dialog appears).", spec: SPEC.menu,
    steps: [pre("user.full_name", ["granted"], "Allow user.full_name first (P03)."),
      A("adera.openPermissionSettings()", "user.full_name: denied", async (lab) => {
        const o = await lab.call("openPermissionSettings", [], SHEET);
        const bad = settingsCheck(lab, o);
        if (bad) return bad;
        return o.value.permissions["user.full_name"] === "denied" ? lab.V.pass(o.value.permissions, "user.full_name: denied") : lab.V.fail(o.value.permissions, "user.full_name: denied");
      }, { timeout: SHEET, mark: true, hint: "Switch **Your name** **off**, then close the sheet." }),
      A("Callback user.consent_changed user.full_name DENIED", "signed callback", cb({ event: "user.consent_changed", capability: "user.full_name", state: "DENIED" })),
      A('adera.requestPermission("user.full_name")', '"granted" after a dialog', expectCall("requestPermission", ["user.full_name"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "A dialog must appear (the switch reset the count). Tap **Allow**." }),
      Q("Did the dialog appear?")],
  });
  t("S05", G.S, "user.basic has no switch of its own", {
    goal: "The menu cannot switch user.basic off alone (the API answers 409 use_disconnect); Disconnect is the way.", spec: SPEC.menu,
    mustDo: "In the sheet, do not offer a working switch for user.basic alone; offer Disconnect (the API refuses with 409 use_disconnect).",
    steps: [H("Open the permission sheet (super app menu -> Permissions)."), Q("Is there NO switch that turns off **Your Adera id** alone (it is absent, fixed, or Disconnect is offered instead)?", { yes: "pass", no: "fail" })],
  });
  t("S06", G.S, "The sheet opened from the super app's menu; the page re-checks", {
    goal: "The menu sheet always works, also without the page asking. Nothing tells the page that a permission changed; the page must re-check (getPermission).", spec: SPEC.menu,
    mustDo: "Offer the permission sheet in every mini app's top menu.",
    steps: [A("Remember every permission's state", "a snapshot", async (lab, ctx) => {
      if (!lab.win.adera) return lab.gate({ kind: "no-bridge" });
      ctx.before = await snapshot(lab);
      ctx.inBefore = lab.monitor.entries.filter((e) => e.dir === "in").length;
      return lab.V.info(ctx.before, "Snapshot taken.");
    }), H("Without using Bridge Lab's buttons: open the super app's menu for Bridge Lab -> **Permissions**, switch **Your approximate location** to the other position, close the sheet."),
      A("Compare with the snapshot", "location.coarse changed", async (lab, ctx) => {
        const after = await snapshot(lab);
        const changed = Object.keys(after).filter((k) => after[k] !== (ctx.before || {})[k]);
        const act = { changed, before: (ctx.before || {})["location.coarse"], after: after["location.coarse"] };
        return changed.includes("location.coarse") ? lab.V.pass(act, "location.coarse changed", "No bridge message announced it (none is specified): the page found it by asking.") : lab.V.fail(act, "location.coarse changed");
      })],
  });
  t("S07", G.S, "Disconnect", {
    goal: "Disconnect sets everything to DENIED (count 0), sends user.disconnected to the partner and the profile is refused.", spec: SPEC.disconnect,
    mustDo: "Offer Disconnect (DELETE /api/v1/me/apps/{app}/permissions) in the mini app's menu.",
    steps: [H("In the super app's menu for Bridge Lab tap **Disconnect** and confirm. If the super app closes Bridge Lab, reopen it and come back to this test."),
      A("Callback user.disconnected", "signed callback listing what was taken back", cb({ event: "user.disconnected" }, 60000)),
      A("Backend: profile", "403 consent_required for user.basic, DENIED (unless already reopened)", (lab) => profileCheck(lab, null, (d, st, b) => {
        if (st === 403 && b.capability === "user.basic") return lab.V.pass(b, "403 user.basic DENIED");
        if (st === 200) return lab.V.info(b, "200: Bridge Lab was reopened after Disconnect (launch reconnects user.basic). Run this right after Disconnect to see the 403.", "403");
        return lab.V.fail({ status: st, body: b }, "403 consent_required user.basic");
      }, "403")),
      Q("After Disconnect, did the super app close Bridge Lab or show a clear message?", { yes: "info", no: "info", expect: "recorded (not specified)" })],
  });
  t("S08", G.S, "Reopening after Disconnect reconnects user.basic", {
    goal: "A new launch after Disconnect turns user.basic back on (callback user.consent_changed user.basic GRANTED); the other permissions stay denied.", spec: SPEC.launchApi,
    steps: [H("Close Bridge Lab and open it again from the catalog, then come back to this test."),
      A("Callback user.consent_changed user.basic GRANTED", "signed callback", async (lab) => lab.expectCallback({ event: "user.consent_changed", capability: "user.basic", state: "GRANTED" }, 30000, 0)),
      A("Backend: profile", "200 with user.basic", (lab) => profileCheck(lab, null, (d, st, b) => (st === 200 && (b.granted || []).includes("user.basic") ? lab.V.pass(b, "200") : lab.V.fail({ status: st, body: b }, "200 with user.basic")), "200"))],
  });
  t("S09", G.S, "The super app's settings screen lists Bridge Lab", {
    goal: "GET /api/v1/me/permissions feeds a settings screen: every app that holds a permission, with what it can see.", spec: SPEC.overview,
    mustDo: "Build the settings screen (GET /api/v1/me/permissions): each app with its permissions and a way to change them.",
    steps: [H("Open the super app's settings -> apps and permissions."), Q("Is Bridge Lab listed with what it can see?", { yes: "pass", no: "notdev", notdevText: "No settings screen lists the apps that hold permissions." })],
  });

  // ============================================================================================== Location
  t("LO01", G.LO, "getLocation() before consent -> not_asked, no dialog", {
    goal: "getLocation never shows a dialog by itself; without consent it says the state.", spec: SPEC.methods, mustDo: null,
    steps: [pre("location.fine", ["not_asked"], "Needs a user never asked for location.fine; denied/blocked are acceptable answers too."),
      A("adera.getLocation()", '{"state": "not_asked"} (or denied/blocked)', async (lab) => {
        const o = await lab.call("getLocation", [], 15000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        if (o.kind === "rejected") return lab.V.fail(lab.show(o), '{"state": "not_asked"}');
        if (o.violations.length) return lab.V.fail(lab.show(o), '{"state": ...}', o.violations.join("; "));
        if (o.value.state === "granted") return lab.V.info(lab.show(o), "Location is already granted: this test needs a user without it.");
        return lab.V.pass(lab.show(o), '{"state": "not_asked"}');
      }), Q("Did any dialog appear (Adera's or the phone's)?", { yes: "fail", no: "pass", expect: "no" })],
  });
  t("LO02", G.LO, "Fine location after consent", {
    goal: 'After location.fine is granted, getLocation() returns {"state":"granted","location":{latitude,longitude,accuracyMeters,at}}; the phone\'s own dialog comes after ours the first time.', spec: SPEC.methods, mustDo: null,
    steps: [A('adera.requestPermission("location.fine")', '"granted"', expectCall("requestPermission", ["location.fine"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**." }),
      A("adera.getLocation()", "a reading", async (lab) => {
        const o = await lab.call("getLocation", [], 60000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        if (o.kind === "rejected") return lab.V.fail(lab.show(o), "a reading");
        if (o.violations.length) return lab.V.fail(lab.show(o), "a valid reading", o.violations.join("; "));
        return o.value.state === "granted" && o.value.location ? lab.V.pass(lab.show(o), "a reading") : lab.V.fail(lab.show(o), "state granted with location", "If the phone's location permission is off, allow it and run again.");
      }, { timeout: 60000, hint: "The first time the phone's own location dialog may appear: allow it." }),
      Q("The first time: did the phone's own location dialog appear after Adera's (or was the phone permission already given)?", { yes: "pass", no: "info", expect: "yes (or already given)" })],
  });
  t("LO03", G.LO, "Coarse location gives a place, never coordinates", {
    goal: 'With {"precision":"coarse"} getLocation needs location.coarse and returns only {"place":{countryCode,region,city}}.', spec: SPEC.methods, mustDo: null,
    steps: [A('adera.requestPermission("location.coarse")', '"granted"', expectCall("requestPermission", ["location.coarse"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**." }),
      A('adera.getLocation({"precision": "coarse"})', "a place, no coordinates", async (lab) => {
        const o = await lab.call("getLocation", [{ precision: "coarse" }], 60000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        if (o.kind === "rejected") return lab.V.fail(lab.show(o), "a place");
        if (o.violations.length) return lab.V.fail(lab.show(o), "a place, no coordinates", o.violations.join("; "));
        return o.value.state === "granted" && o.value.place && !o.value.location ? lab.V.pass(lab.show(o), "a place") : lab.V.fail(lab.show(o), "state granted with place only");
      }, { timeout: 60000 })],
  });
  t("LO04", G.LO, "precision coarse without location.coarse consent -> not granted", {
    goal: "Coarse needs location.coarse (location.fine does not count).", spec: SPEC.methods,
    steps: [pre("location.coarse", ["not_asked", "denied", "blocked"], "Switch **Your approximate location** off in the menu first."),
      A('adera.getLocation({"precision": "coarse"})', "state not_asked, denied or blocked; no place", async (lab) => {
        const o = await lab.call("getLocation", [{ precision: "coarse" }], 15000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        if (o.kind === "rejected") return lab.V.fail(lab.show(o), "a state");
        return ["not_asked", "denied", "blocked"].includes(o.value.state) && !o.value.place && !o.value.location ? lab.V.pass(lab.show(o), "not granted, no reading") : lab.V.fail(lab.show(o), "not granted, no reading");
      })],
  });
  t("LO05", G.LO, "Phone permission off -> denied/blocked without saying which", {
    goal: "A reading needs both our consent and the phone's permission; otherwise denied or blocked, without telling the mini app which of the two refused.", spec: SPEC.methods,
    steps: [pre("location.fine", ["granted"], "Allow location.fine first (LO02)."),
      H("Phone settings -> Apps -> the super app -> Permissions -> Location: **Don't allow**. Come back to Bridge Lab."),
      A("adera.getLocation()", '{"state": "denied"} or "blocked", nothing else', async (lab) => {
        const o = await lab.call("getLocation", [], 30000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        if (o.kind === "rejected") return lab.V.fail(lab.show(o), "denied or blocked", "Rejected instead of answering a state.");
        const extra = Object.keys(o.value).filter((k) => k !== "state");
        if (!["denied", "blocked"].includes(o.value.state)) return lab.V.fail(lab.show(o), "denied or blocked");
        return extra.length ? lab.V.fail(lab.show(o), "only {state}", "Extra fields (" + extra.join(", ") + ") may tell which side refused.") : lab.V.pass(lab.show(o), "denied or blocked");
      }, { timeout: 30000 }),
      H("Give the super app its location permission back.")],
  });
  t("LO06", G.LO, "Our consent switched off -> no reading", {
    goal: "getLocation checks the consent on the server before reading the phone (no cached yes).", spec: SPEC.rules, mustDo: "getLocation checks the consent on the server (GET .../permissions) every time, then reads the phone.",
    steps: [pre("location.fine", ["granted"], "Allow location.fine first (LO02)."),
      H("Super app menu for Bridge Lab -> **Permissions**: switch **Your precise location** off. Close the sheet."),
      A("adera.getLocation()", '{"state": "denied"}', async (lab) => {
        const o = await lab.call("getLocation", [], 15000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        if (o.kind === "rejected") return lab.V.fail(lab.show(o), '{"state": "denied"}');
        return o.value.state === "denied" && !o.value.location ? lab.V.pass(lab.show(o), '{"state": "denied"}') : lab.V.fail(lab.show(o), '{"state": "denied"}', o.value.location ? "A reading came although our consent is off (cached?)." : "");
      })],
  });
  t("LO07", G.LO, "Location services off on the phone", {
    goal: "Not specified: what getLocation answers when the phone's location services are off.", spec: SPEC.methods,
    mustDo: "Not specified yet: decide and document what getLocation answers when location services are off (proposal: {\"state\":\"denied\"} like a refused phone permission, or error service_unavailable).",
    steps: [pre("location.fine", ["granted"], "Allow location.fine first."), H("Turn the phone's location services off (quick settings)."),
      A("adera.getLocation()", "recorded (not specified)", async (lab) => {
        const o = await lab.call("getLocation", [], 30000);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        return lab.V.info(lab.show(o), "Recorded. Not specified by the spec: see 'what the super app must do'.", "not specified");
      }, { timeout: 30000 }), H("Turn location services back on.")],
  });
  t("LO08", G.LO, "Unknown options", {
    goal: "Not specified: getLocation with an unknown precision. invalid_params would match the rest of the spec.", spec: SPEC.errors,
    mustDo: "Not specified yet: reject unknown getLocation options with invalid_params (proposal), and document it.",
    steps: [A('adera.getLocation({"precision": "exact"})', "rejects invalid_params (proposal)", async (lab) => {
      const o = await lab.call("getLocation", [{ precision: "exact" }], 15000);
      const g = lab.gate(o, lab.monitor);
      if (g) return g;
      return o.kind === "rejected" && o.error.code === "invalid_params" ? lab.V.pass(lab.show(o), "invalid_params") : lab.V.info(lab.show(o), "Recorded (not specified).", "invalid_params (proposal)");
    })],
  });

  // ============================================================================================== Profile (backend)
  t("PR01", G.PR, "Profile with only user.basic", {
    goal: "GET /v1/users/{alias}/profile, signed: 200 with the alias (= the token's sub) and granted.", spec: SPEC.profile, mustDo: null,
    steps: [A("Backend: GET /v1/users/{sub}/profile", "200, alias = sub, granted contains user.basic", (lab) => profileCheck(lab, null, (d, st, b) => {
      const sub = lab.launch && lab.launch.claims && lab.launch.claims.sub;
      return st === 200 && b.alias === sub && (b.granted || []).includes("user.basic") ? lab.V.pass(b, "200") : lab.V.fail({ status: st, body: b, sub }, "200, alias = sub");
    }, "200"))],
  });
  t("PR02", G.PR, "Adera's response signature verifies", {
    goal: "Every API answer is signed by Adera (ADERA-SIG-1-RESPONSE over status, body hash and the request's signature).", spec: SPEC.responses,
    steps: [A("Backend: profile, then check X-Adera-Signature of the answer", "verified", (lab) => profileCheck(lab, null, (d) => (d.responseSignature && d.responseSignature.verified ? lab.V.pass(d.responseSignature, "verified") : lab.V.fail(d.responseSignature, "verified")), "verified"))],
  });
  t("PR03", G.PR, "?fields= for a permission not allowed -> 403 consent_required", {
    goal: "A backend that needs a field says so with ?fields=; if the user did not allow it the answer is 403 with the capability and the state the page sees.", spec: SPEC.profile,
    steps: [A("Find a user.* permission that is not granted and ask the profile for it", "403 consent_required with the same state", async (lab) => {
      if (!lab.win.adera) return lab.gate({ kind: "no-bridge" });
      let cap = null;
      let st = null;
      for (const c of ["user.full_name", "user.contact", "user.address"]) { st = await lab.state(c); if (st && st !== "granted") { cap = c; break; } }
      if (!cap) return lab.V.info("all granted", "Switch one of name, contact, address off first.", "403");
      return profileCheck(lab, [cap], (d, status, b) => (status === 403 && b.error === "consent_required" && b.capability === cap && b.state === upper(st) ? lab.V.pass(b, "403 " + upper(st)) : lab.V.fail({ status, body: b, pageState: st }, "403 consent_required " + upper(st))), "403");
    })],
  });
  t("PR04", G.PR, "Full name after the user allowed it", {
    goal: "With user.full_name granted, the profile carries fullName.", spec: SPEC.profile,
    steps: [A('adera.requestPermission("user.full_name")', '"granted"', expectCall("requestPermission", ["user.full_name"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**." }),
      A("Backend: profile ?fields=user.full_name", "200 with fullName", (lab) => profileCheck(lab, ["user.full_name"], (d, st, b) => (st === 200 && b.fullName ? lab.V.pass(b, "fullName") : lab.V.fail({ status: st, body: b }, "200 with fullName")), "200"))],
  });
  t("PR05", G.PR, "Contact and address after the user allowed them", {
    goal: "user.contact gives phone and email (each if the user has one); user.address gives the saved place (country, region, city).", spec: SPEC.profile,
    steps: [A('adera.requestPermission("user.contact")', '"granted"', expectCall("requestPermission", ["user.contact"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**. If it says blocked, switch it on in the menu." }),
      A('adera.requestPermission("user.address")', '"granted"', expectCall("requestPermission", ["user.address"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**." }),
      A("Backend: profile ?fields=user.contact,user.address", "200 with phone and/or email, and address", (lab) => profileCheck(lab, ["user.contact", "user.address"], (d, st, b) => {
        if (st !== 200) return lab.V.fail({ status: st, body: b }, "200");
        const has = { phoneOrEmail: !!(b.phone || b.email), address: !!b.address };
        return has.phoneOrEmail ? lab.V.pass(b, "contact (+ address if the user saved a place)", has.address ? undefined : "No address: the user has no saved place (not an error).") : lab.V.fail(b, "phone or email");
      }, "200"))],
  });
  t("PR06", G.PR, "DEV synthetic users", {
    goal: "On DEV, u_p_test_ok answers fake data, u_p_test_noconsent/denied/blocked refuse with their state, u_p_test_unreachable answers 422.", spec: SPEC.synthetic, mustDo: null,
    steps: [A("Backend: profile ?fields=user.full_name for the five test ids", "200, 403 NOT_ASKED, 403 DENIED, 403 BLOCKED, 422", async (lab) => {
      const want = { ok: [200, null], noconsent: [403, "NOT_ASKED"], denied: [403, "DENIED"], blocked: [403, "BLOCKED"], unreachable: [422, null] };
      const act = {};
      let good = true;
      for (const [k, [code, state]] of Object.entries(want)) {
        const r = await lab.profile(["user.full_name"], "u_p_test_" + k);
        const d = r.data || {};
        act[k] = d.status + " " + JSON.stringify(d.body);
        if (d.status !== code || (state && (d.body || {}).state !== state)) good = false;
      }
      return good ? lab.V.pass(act, "as documented") : lab.V.fail(act, "200, 403 NOT_ASKED, 403 DENIED, 403 BLOCKED, 422");
    })],
  });
  t("PR07", G.PR, "Unknown alias -> 422 user_unreachable", {
    goal: "An id never issued to this app is unreachable (never 404, never a hint whether the person exists).", spec: SPEC.profile, mustDo: null,
    steps: [A("Backend: profile of u_p_0000...dead", "422 user_unreachable", async (lab) => {
      const r = await lab.profile(null, "u_p_0000000000000000000000000000dead");
      const d = r.data || {};
      return d.status === 422 && (d.body || {}).error === "user_unreachable" ? lab.V.pass(d.body, "422") : lab.V.fail({ status: d.status, body: d.body }, "422 user_unreachable");
    })],
  });
  t("PR08", G.PR, "Bad partner signatures are refused", {
    goal: "Every partner call must be signed with the app's key: wrong key, old timestamp, unknown key id, missing headers are refused.", spec: SPEC.authErrors, mustDo: null,
    steps: [A("Backend: 4 badly signed profile calls", "401 invalid_signature, stale_timestamp, unknown_key, missing_signature", async (lab) => {
      const want = { "bad-signature": "invalid_signature", "stale-timestamp": "stale_timestamp", "unknown-key": "unknown_key", "missing-signature": "missing_signature" };
      const act = {};
      let good = true;
      for (const [mode, code] of Object.entries(want)) {
        const r = await lab.profile(null, "u_p_test_ok", mode);
        const d = r.data || {};
        act[mode] = d.status + " " + ((d.body || {}).error || "");
        if (d.status !== 401 || (d.body || {}).error !== code) good = false;
      }
      return good ? lab.V.pass(act, "all refused with the right code") : lab.V.fail(act, "401 with the documented codes");
    })],
  });

  // ============================================================================================== Notifications
  t("N01", G.N, "Bridge Lab's approved templates", {
    goal: "A partner sends only staff-approved templates; GET /v1/templates lists them.", spec: "partner-api/templates.html", mustDo: null,
    steps: [A("Backend: GET /v1/templates", "bridge-lab-inapp and bridge-lab-direct", async (lab) => {
      const r = await lab.api("GET", "/api/partner/templates");
      const d = r.data || {};
      const codes = Array.isArray(d.body) ? d.body.map((x) => x.code) : d.body;
      return d.status === 200 && codes.includes("bridge-lab-inapp") && codes.includes("bridge-lab-direct") ? lab.V.pass(codes, "both") : lab.V.fail({ status: d.status, codes }, "both templates");
    })],
  });
  t("N02", G.N, "Default deny before notify.inapp is allowed", {
    goal: "A notification goes out only while the channel's consent is GRANTED; NOT_ASKED is refused too.", spec: SPEC.notify, mustDo: null,
    steps: [pre("notify.inapp", ["not_asked", "denied", "blocked"], "Switch **Notifications in Adera** off in the menu first."),
      A("Backend: send an in-app message", "403 consent_required notify.inapp with the page's state", async (lab) => {
        const st = await lab.state("notify.inapp");
        const { r, verdict } = await sendNotification(lab, "inapp");
        if (verdict) return verdict;
        return r.status === 403 && (r.body || {}).error === "consent_required" && (r.body || {}).capability === "notify.inapp" && (r.body || {}).state === upper(st) ? lab.V.pass(r.body, "403 " + upper(st)) : lab.V.fail({ status: r.status, body: r.body, pageState: st }, "403 consent_required " + upper(st));
      })],
  });
  t("N03", G.N, "In-app message after the page asked for notify.inapp", {
    goal: "The page asks for notify.inapp; then the backend's message is accepted (202), delivered (SENT) and shows in the bell.", spec: SPEC.notify, mustDo: null,
    steps: [A('adera.requestPermission("notify.inapp")', '"granted"', expectCall("requestPermission", ["notify.inapp"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**." }),
      A("Backend: send an in-app message", "202 QUEUED, channel IN_APP", async (lab, ctx) => {
        const { r, verdict } = await sendNotification(lab, "inapp", { params: { text: "Hello from Bridge Lab (N03)" } });
        if (verdict) return verdict;
        ctx.ext = (r.body || {}).externalId;
        return r.status === 202 && r.body.status === "QUEUED" && r.body.channel === "IN_APP" ? lab.V.pass(r.body, "202 IN_APP") : lab.V.fail({ status: r.status, body: r.body }, "202 QUEUED IN_APP");
      }, { mark: true }),
      A("Backend: poll GET /v1/notifications/{externalId} until final", "SENT", async (lab, ctx) => {
        for (let i = 0; i < 15; i++) {
          const r = await lab.api("GET", "/api/partner/notifications/" + ctx.ext);
          const b = (r.data || {}).body || {};
          if (["SENT", "FAILED", "UNREACHABLE", "SUPPRESSED", "EXPIRED"].includes(b.status)) return b.status === "SENT" ? lab.V.pass(b, "SENT") : lab.V.fail(b, "SENT");
          await sleep(1500);
        }
        return lab.V.fail("still not final after 22 s", "SENT");
      }, { timeout: 30000 }),
      Q("Is \"Hello from Bridge Lab (N03)\" in the super app's bell / inbox?", { yes: "pass", no: "notdev", notdevText: "The message reached the inbox API but the super app does not show it." })],
  });
  t("N04", G.N, "SMS after notify.sms was allowed", {
    goal: "A DIRECT template goes by SMS when the user has a phone and SMS is allowed (dev: console SMS, no real message).", spec: SPEC.channels, mustDo: null,
    steps: [A('adera.requestPermission("notify.sms")', '"granted"', expectCall("requestPermission", ["notify.sms"], ["granted"], { timeout: DIALOG }), { timeout: DIALOG, hint: "If a dialog appears, tap **Allow**. Blocked? switch it on in the menu." }),
      A("Backend: send the DIRECT template", "202, channel SMS", async (lab) => {
        const { r, verdict } = await sendNotification(lab, "direct", { params: { text: "SMS test (N04)" } });
        if (verdict) return verdict;
        if (r.status === 429) return lab.V.info(r.body, "Today's SMS quota for this user is used up (N08): try tomorrow.", "202 SMS");
        return r.status === 202 && r.body.channel === "SMS" ? lab.V.pass(r.body, "202 SMS") : lab.V.fail({ status: r.status, body: r.body }, "202 SMS", r.status === 202 ? "Another channel was chosen (does the user have a phone?)." : "");
      }),
      Q("On the laptop: does http://127.0.0.1:8085/dev/messages (or the notification-service log) show \"Bridge Lab: SMS test (N04)\"?", { yes: "pass", no: "fail" })],
  });
  t("N05", G.N, "SMS refused -> 403, no fallback to email", {
    goal: "If the user did not allow the chosen channel the request is refused; it is never re-sent by another channel.", spec: SPEC.channels, mustDo: null,
    steps: [H("Super app menu for Bridge Lab -> Permissions: switch **Text messages (SMS)** off."),
      A("Backend: send the DIRECT template", "403 consent_required notify.sms (not 202 by email)", async (lab) => {
        const { r, verdict } = await sendNotification(lab, "direct", { params: { text: "must not arrive (N05)" } });
        if (verdict) return verdict;
        return r.status === 403 && (r.body || {}).capability === "notify.sms" ? lab.V.pass(r.body, "403 notify.sms") : lab.V.fail({ status: r.status, body: r.body }, "403 consent_required notify.sms");
      })],
  });
  t("N06", G.N, "Muting SMS in the super app is a consent change", {
    goal: "The app's mute switches are the notification consent: muting SMS sends user.consent_changed notify.sms DENIED and the next SMS is refused.", spec: SPEC.consentEvents,
    mustDo: "Offer the per-app mute switches (they are the notify.* consent: PUT /api/v1/me/apps/{id}/preferences or the permission switches).",
    steps: [pre("notify.sms", ["granted"], "Allow SMS first (N04)."),
      H("In the super app, open Bridge Lab's notification settings (mute) and **mute SMS**."),
      A("Callback user.consent_changed notify.sms DENIED", "signed callback", cb({ event: "user.consent_changed", capability: "notify.sms", state: "DENIED" }, 30000)),
      A("Backend: send the DIRECT template", "403 consent_required", async (lab) => {
        const { r, verdict } = await sendNotification(lab, "direct", { params: { text: "muted (N06)" } });
        if (verdict) return verdict;
        return r.status === 403 && (r.body || {}).error === "consent_required" ? lab.V.pass(r.body, "403") : lab.V.fail({ status: r.status, body: r.body }, "403 consent_required");
      }), H("Unmute SMS again.")],
  });
  t("N07", G.N, "Idempotency on externalId", {
    goal: "The same request again answers 200 replayed:true (nothing sent twice); the same externalId with other content is 409.", spec: SPEC.idempotency, mustDo: null,
    steps: [pre("notify.inapp", ["granted"], "Allow notify.inapp first (N03)."),
      A("Send an in-app message with a fixed externalId", "202", async (lab, ctx) => {
        ctx.ext = "lab-idem-" + Date.now().toString(36);
        const { r, verdict } = await sendNotification(lab, "inapp", { externalId: ctx.ext, params: { text: "idempotency (N07)" }, callback: false });
        if (verdict) return verdict;
        return r.status === 202 ? lab.V.pass(r.body, "202") : lab.V.fail({ status: r.status, body: r.body }, "202");
      }),
      A("The same request again", "200 replayed: true", async (lab, ctx) => {
        const { r, verdict } = await sendNotification(lab, "inapp", { externalId: ctx.ext, params: { text: "idempotency (N07)" }, callback: false });
        if (verdict) return verdict;
        return r.status === 200 && r.body.replayed === true ? lab.V.pass(r.body, "replayed") : lab.V.fail({ status: r.status, body: r.body }, "200 replayed");
      }),
      A("The same externalId, other text", "409 external_id_reused", async (lab, ctx) => {
        const { r, verdict } = await sendNotification(lab, "inapp", { externalId: ctx.ext, params: { text: "changed (N07)" }, callback: false });
        if (verdict) return verdict;
        return r.status === 409 && (r.body || {}).error === "external_id_reused" ? lab.V.pass(r.body, "409") : lab.V.fail({ status: r.status, body: r.body }, "409 external_id_reused");
      })],
  });
  t("N08", G.N, "Daily SMS quota -> 429 quota_exceeded", {
    goal: "Default SMS limit: 3 per user per day; the next is 429 quota_exceeded max_per_user_per_day. Uses up today's SMS for this user.", spec: SPEC.quota, mustDo: null,
    steps: [pre("notify.sms", ["granted"], "Allow SMS first (N04)."),
      A("Backend: send SMS until refused (at most 5)", "429 quota_exceeded max_per_user_per_day SMS", async (lab) => {
        const seen = [];
        for (let i = 0; i < 5; i++) {
          const { r, verdict } = await sendNotification(lab, "direct", { params: { text: "quota " + (i + 1) + " (N08)" }, callback: false });
          if (verdict) return verdict;
          seen.push(r.status + " " + ((r.body || {}).error || (r.body || {}).status) + ((r.body || {}).limit ? " " + r.body.limit : ""));
          if (r.status === 429) return r.body.error === "quota_exceeded" && r.body.limit === "max_per_user_per_day" && r.body.channel === "SMS" ? lab.V.pass(seen, "429 max_per_user_per_day") : lab.V.fail(seen, "429 quota_exceeded max_per_user_per_day SMS");
          if (r.status !== 202) return lab.V.fail(seen, "202 then 429");
        }
        return lab.V.fail(seen, "a 429 within 5 sends", "No quota hit: were the SMS limits changed for Bridge Lab?");
      })],
  });
  t("N09", G.N, "Final status callback notification.status", {
    goal: "With a callbackUrl Adera POSTs the final status, signed.", spec: SPEC.statusCallbacks, mustDo: null,
    steps: [pre("notify.inapp", ["granted"], "Allow notify.inapp first (N03)."),
      A("Backend: send an in-app message with Bridge Lab's callback URL", "202", async (lab, ctx) => {
        const { r, verdict } = await sendNotification(lab, "inapp", { params: { text: "status callback (N09)" } });
        if (verdict) return verdict;
        ctx.ext = (r.body || {}).externalId;
        return r.status === 202 ? lab.V.pass(r.body, "202") : lab.V.fail({ status: r.status, body: r.body }, "202");
      }, { mark: true }),
      A("Callback notification.status SENT for that externalId", "signed callback", async (lab, ctx) => lab.expectCallback({ event: "notification.status", externalId: ctx.ext }, 40000, ctx.mark), { timeout: 40000 })],
  });

  // ============================================================================================== Callbacks
  t("C01", G.C, "The receiver refuses forged callbacks", {
    goal: "Bridge Lab's receiver checks Adera's signature (JWKS) before trusting a callback; an unsigned or wrongly signed one is refused.", spec: SPEC.callbacks, mustDo: null,
    steps: [A("Self-test: post an unsigned callback to the receiver", "401", async (lab) => { const r = await lab.api("POST", "/api/callbacks/selftest", { kind: "unsigned" }); return (r.data || {}).status === 401 ? lab.V.pass(r.data, "401") : lab.V.fail(r.data, "401"); }),
      A("Self-test: post a callback signed with another key under Adera's key id", "401", async (lab) => { const r = await lab.api("POST", "/api/callbacks/selftest", { kind: "forged" }); return (r.data || {}).status === 401 ? lab.V.pass(r.data, "401") : lab.V.fail(r.data, "401"); })],
  });
  t("C02", G.C, "user.consent_changed arrives after a change", {
    goal: "Every real change (dialog answer, menu switch, mute) sends a signed user.consent_changed to the app's callback address.", spec: SPEC.consentEvents, mustDo: null,
    steps: [H("Change any permission now: switch one in the super app's menu (or answer a dialog in another test)."),
      A("Wait for user.consent_changed", "a signed callback within 60 s", cb({ event: "user.consent_changed" }, 60000), { timeout: 60000 })],
  });
  t("C03", G.C, "Every callback received verifies against the JWKS", {
    goal: "All callbacks (consent and status) are signed with Adera's key.", spec: SPEC.callbacks, mustDo: null,
    steps: [A("Check every callback received for this user", "all verified", async (lab) => {
      const list = (await lab.callbacks(0)).filter((c) => !c.selftest);
      if (!list.length) return lab.V.info("none yet", "Run some permission tests first.", "all verified");
      const bad = list.filter((c) => !c.verified);
      const act = list.map((c) => c.event + " " + ((c.body || {}).capability || (c.body || {}).status || "") + " " + (c.verified ? "verified" : "REJECTED: " + c.detail));
      return bad.length ? lab.V.fail(act, "all verified") : lab.V.pass(act, "all verified");
    })],
  });
  t("C04", G.C, "Duplicate deliveries are ignored", {
    goal: "Delivery is at least once: de-duplicate on Idempotency-Key.", spec: SPEC.callbacks, mustDo: null,
    steps: [A("Self-test: deliver the last real callback again", "recognised as a duplicate", async (lab) => {
      const r = await lab.api("POST", "/api/callbacks/selftest", { kind: "duplicate" });
      if (r.status === 409) return lab.V.info(r.data, "No real callback received yet: change a permission first.", "duplicate");
      const d = r.data || {};
      if (d.status === 401) return lab.V.info(d, "The stored callback is older than 5 minutes (timestamp check): change a permission, then run again.", "duplicate");
      return d.duplicate ? lab.V.pass(d, "duplicate") : lab.V.fail(d, "duplicate");
    })],
  });
  t("C05", G.C, "user.disconnected lists everything taken back", {
    goal: "One user.disconnected per Disconnect, with capabilities = everything taken back (user.basic included).", spec: SPEC.consentEvents, mustDo: null,
    steps: [A("Find the last user.disconnected", "capabilities includes user.basic", async (lab) => {
      const list = (await lab.callbacks(0)).filter((c) => c.event === "user.disconnected" && !c.selftest);
      const last = list[list.length - 1];
      if (!last) return lab.V.info("none", "Run S07 (Disconnect) first.", "a user.disconnected");
      const caps = (last.body || {}).capabilities;
      return Array.isArray(caps) && caps.includes("user.basic") ? lab.V.pass(last.body, "includes user.basic") : lab.V.fail(last.body, "capabilities with user.basic");
    })],
  });
  t("C06", G.C, "Events are ordered by at", {
    goal: "A late event can arrive after a newer one; a receiver orders by at. Bridge Lab reports the order it saw.", spec: SPEC.callbacks, mustDo: null,
    steps: [A("Compare arrival order with the at field", "in order (out of order is allowed, recorded)", async (lab) => {
      const list = (await lab.callbacks(0)).filter((c) => c.event && c.event.indexOf("user.") === 0 && !c.selftest && c.body && c.body.at);
      if (list.length < 2) return lab.V.info(list.length, "Fewer than two consent callbacks so far.");
      const late = [];
      for (let i = 1; i < list.length; i++) if (list[i].body.at < list[i - 1].body.at) late.push(list[i].body);
      return late.length ? lab.V.info(late, "Some arrived late (allowed by the spec; a receiver must order by at).") : lab.V.pass(list.length + " callbacks in order", "in order");
    })],
  });

  // ============================================================================================== Deep links
  const routeCheck = (want, extraQuery) => A("Check the address Bridge Lab was opened at", "path " + want + (extraQuery ? " + " + extraQuery : "") + ", entryUrl's query kept, a token", async (lab) => {
    const o = lab.openedAt;
    const ageS = Math.round((Date.now() - o.ms) / 1000);
    const act = { path: o.pathname, query: Object.assign({}, o.query, o.query.launch ? { launch: "<token>" } : {}), hash: o.hash, openedSecondsAgo: ageS };
    if (!o.launchToken) return lab.V.notdev("Opened without a launch token.", lab.MUST.launch, SPEC.banner, act);
    const probs = [];
    if (o.pathname !== want) probs.push("path is " + o.pathname);
    if (o.href.replace(/^https?:\/\//, "").indexOf("//") >= 0) probs.push("a double slash in the address");
    const entry = new URL(lab.config.entryUrl);
    for (const [k, v] of entry.searchParams) if (o.query[k] !== v) probs.push("entryUrl's " + k + "=" + v + " dropped");
    if (extraQuery) { const [k, v] = extraQuery.split("="); if (o.query[k] !== v) probs.push(extraQuery + " missing"); }
    if (o.origin !== entry.origin) probs.push("host changed to " + o.origin);
    return probs.length ? lab.V.fail(act, want, probs.join("; ")) : lab.V.pass(act, want);
  });
  t("D01", G.D, "Where was I opened?", {
    goal: "Shows the path, query and hash this page was opened with (deep links put the route into the path).", spec: SPEC.banner, mustDo: null,
    steps: [A("Read the address", "recorded", async (lab) => {
      const o = lab.openedAt;
      return lab.V.info({ path: o.pathname, query: Object.assign({}, o.query, o.query.launch ? { launch: "<token>" } : {}), hash: o.hash, referrer: o.referrer, openedAt: new Date(o.ms).toISOString() }, "Recorded.");
    })],
  });
  t("D02", G.D, "Banner (MINI_APP) with a route opens Bridge Lab at that route", {
    goal: "A MINI_APP banner with target /deeplink/banner launches the app as usual and puts the route into the path: http://localhost:8095/deeplink/banner?entry=1&launch=...", spec: SPEC.banner,
    mustDo: "For a MINI_APP banner: launch the app (POST .../launch), put the banner's target into the path of the returned url (exactly one slash, keep its query, same host) and load it.",
    pre: ["Staff created the banner (below)."],
    steps: [H("Staff: backoffice -> **Banners** -> new banner, action **Open a mini app**, app **Bridge Lab**, route `/deeplink/banner`, live now."),
      H("In the super app tap that banner. Bridge Lab opens; come back to this test (D02)."), routeCheck("/deeplink/banner")],
  });
  t("D03", G.D, "Banner with no route opens the home", {
    goal: "A MINI_APP banner with no target loads the launch url as it is.", spec: SPEC.banner, mustDo: "For a MINI_APP banner without a target: load the launch url as it is (the app's home).",
    steps: [H("Staff: change the banner's route to empty (no route)."), H("Tap the banner. Come back to this test (D03)."), routeCheck("/")],
  });
  t("D04", G.D, "A route with a query string keeps both queries", {
    goal: "A target like /deeplink/banner?promo=1 keeps its own query and the entryUrl's query and the token.", spec: SPEC.banner,
    mustDo: "Merge the route's query with the launch url's query (entry=1&launch=...): nothing dropped.",
    steps: [H("Staff: set the banner's route to `/deeplink/banner?promo=1` (if the backoffice refuses a query, mark this test Skipped)."), H("Tap the banner. Come back to this test (D04)."), routeCheck("/deeplink/banner", "promo=1")],
  });
  t("D05", G.D, "Notification with a deep link opens Bridge Lab at the route", {
    goal: "An in-app message carries data (here {\"route\":\"/deeplink/notification\",\"ref\":\"D05\"}). The bell guide says: open the mini app and hand it the data; how the mini app receives it is not specified.", spec: SPEC.bell,
    mustDo: "Not specified yet: when the user taps a mini app's notification, open the mini app (normal launch) and hand it the notification's data. Proposal: treat data.route like a banner route (put it into the path) and pass the other keys as query parameters, or define a bridge method that returns the data.",
    steps: [pre("notify.inapp", ["granted"], "Allow notify.inapp first (N03)."),
      A("Backend: send an in-app message with data", "202", async (lab, ctx) => {
        const { r, verdict } = await sendNotification(lab, "inapp", { params: { text: "Tap me (D05)" }, data: { route: "/deeplink/notification", screen: "deeplink", ref: "D05" } });
        if (verdict) return verdict;
        return r.status === 202 ? lab.V.pass(r.body, "202") : lab.V.fail({ status: r.status, body: r.body }, "202");
      }),
      H("Close Bridge Lab. Open the super app's bell and tap **Tap me (D05)**. Bridge Lab opens; come back to this test (D05)."),
      A("What did the page receive?", "path /deeplink/notification (or the data in the URL)", async (lab) => {
        const o = lab.openedAt;
        const age = Math.round((Date.now() - o.ms) / 1000);
        const act = { path: o.pathname, query: Object.assign({}, o.query, o.query.launch ? { launch: "<token>" } : {}), hash: o.hash, openedSecondsAgo: age };
        if (age > 600) return lab.V.info(act, "This page was not opened recently: open Bridge Lab from the notification, then run this step.");
        if (o.pathname === "/deeplink/notification" || /D05/.test(o.search + o.hash)) return lab.V.pass(act, "the route or the data");
        return lab.V.notdev("Bridge Lab opened without the notification's route or data.", undefined, undefined, act);
      })],
  });
  t("D06", G.D, "Push notification tap", {
    goal: "A push carries the same data as the inbox item (strings). The dev stack uses a fake push provider, so this needs a build with real FCM.", spec: SPEC.bell,
    mustDo: "Open the mini app from a push the same way as from the bell (see D05).",
    steps: [H("Only with real FCM: send D05's message again, tap the push."), Q("Did the push open Bridge Lab at /deeplink/notification?", { yes: "pass", no: "notdev", notdevText: "A push tap does not open the mini app with its data." })],
  });

  // ============================================================================================== Session and app state
  t("SA01", G.SA, "App paused while open -> app_not_live", {
    goal: "Pausing takes effect at once: the next bridge call fails with app_not_live; the super app shows that the app is unavailable.", spec: SPEC.errors,
    mustDo: "Map 403 app_not_live to the bridge error app_not_live and show that the app is unavailable.",
    steps: [H("Keep Bridge Lab open. Staff: backoffice -> Apps -> **Bridge Lab** -> status **PAUSED**. Wait 20 s."),
      A('adera.requestPermission("user.basic")', "rejects app_not_live", expectReject("requestPermission", ["user.basic"], "app_not_live")),
      A('adera.getPermission("user.basic")', "rejects app_not_live", expectReject("getPermission", ["user.basic"], "app_not_live")),
      Q("Did the super app tell you that the app is unavailable (or close it)?", { yes: "pass", no: "info", expect: "yes (not specified in detail)" }),
      H("Staff: set Bridge Lab back to **LIVE**.")],
  });
  t("SA02", G.SA, "App killed while open -> app_not_live (do this last)", {
    goal: "A killed app behaves like a paused one for the user; only a SUPER_ADMIN can revive it.", spec: SPEC.errors, mustDo: "Map 403 app_not_live to app_not_live.",
    steps: [H("Staff: set Bridge Lab to **KILLED**. Wait 20 s."), A('adera.requestPermission("user.basic")', "rejects app_not_live", expectReject("requestPermission", ["user.basic"], "app_not_live")),
      H("Revive it: `python3 -I bridge-lab/seed.py --revive` on the laptop (uses a temporary SUPER_ADMIN).")],
  });
  t("SA03", G.SA, "Session expired -> session_expired", {
    goal: "A 401 from the API becomes session_expired; the super app signs the user in again and the mini app may retry.", spec: SPEC.errors,
    mustDo: "Map a 401 to session_expired, sign the user in again, keep the mini app open so it can retry.",
    steps: [H("End the user's session while Bridge Lab stays open: on the laptop, `curl -X DELETE http://127.0.0.1:4434/admin/identities/<user id>/sessions` (the user id is in the backoffice, Users)."),
      A('adera.requestPermission("user.basic")', "rejects session_expired", expectReject("requestPermission", ["user.basic"], "session_expired", { timeout: 30000 }), { timeout: 30000 }),
      Q("Did the super app ask you to sign in again?", { yes: "pass", no: "notdev", notdevText: "The super app does not lead the user back through sign-in." }),
      A('After signing in: adera.getPermission("user.basic")', "a state again", expectCall("getPermission", ["user.basic"], ["granted", "denied"]))],
  });
  t("SA04", G.SA, "Account suspended -> account_not_active", {
    goal: "403 account_suspended / account_deleted becomes account_not_active; the super app signs the user out.", spec: SPEC.errors,
    mustDo: "Map 403 account_suspended / account_deleted to account_not_active and sign the user out.",
    steps: [H("Staff: backoffice -> Users -> this test user -> **Suspend**."), A('adera.requestPermission("user.basic")', "rejects account_not_active", expectReject("requestPermission", ["user.basic"], "account_not_active", { timeout: 20000 }), { timeout: 20000 }),
      Q("Did the super app sign you out (or say the account is suspended)?", { yes: "pass", no: "info" }), H("Staff: reactivate the user.")],
  });
  t("SA05", G.SA, "No network -> service_unavailable", {
    goal: "With no network the bridge rejects with service_unavailable (retry later). Note: over USB with adb reverse the phone still reaches the laptop; use Wi-Fi to the laptop for this test.", spec: SPEC.errors,
    mustDo: "Map no network / 503 to service_unavailable.",
    steps: [H("Turn on airplane mode (and Wi-Fi off)."), A('adera.getPermission("user.basic")', "rejects service_unavailable", expectReject("getPermission", ["user.basic"], "service_unavailable", { timeout: 30000 }), { timeout: 30000 }),
      H("Turn the network back on."), A('adera.getPermission("user.basic")', "a state again", expectCall("getPermission", ["user.basic"], ["granted", "denied"]))],
  });
  t("SA06", G.SA, "Sent to the background and back", {
    goal: "After the app was in the background the page is still alive and the bridge still answers.", spec: SPEC.methods,
    steps: [H("Press the phone's Home button, wait 10 seconds, return to the super app (Bridge Lab still open)."),
      A("Look for visibility changes and call the bridge", "hidden then visible; an answer", async (lab, ctx) => {
        const ev = (lab.store.read("visibility") || []).filter((e) => e.ms > (ctx.openedMs || 0));
        const o = await lab.call("getPermission", ["user.basic"]);
        const g = lab.gate(o, lab.monitor);
        if (g) return g;
        const act = { visibilityEvents: ev.map((e) => e.state + " " + new Date(e.ms).toISOString().slice(11, 19)), answer: lab.show(o) };
        return ev.some((e) => e.state === "hidden") ? lab.V.pass(act, "hidden then visible, answered") : lab.V.info(act, "No 'hidden' event seen since this test was opened: did the app go to the background?");
      })],
  });
  t("SA07", G.SA, "Reload after the launch token expired", {
    goal: "Not specified: after 5 minutes the token in the URL is expired; a reload loads the same URL.", spec: SPEC.launchApi,
    mustDo: "Not specified yet: decide what a reload (or a return after a long time) does. Proposal: the super app intercepts reloads of the mini app and launches again (new token), or offers a bridge method for a fresh token.",
    steps: [H("Wait until Bridge Lab has been open more than 5 minutes."), goTo("Reload the page", "reload"),
      A("After the reload: is the token in the URL still valid?", "a fresh valid token (proposal)", async (lab) => {
        if (!launchToken(lab)) return lab.V.info("no token in the URL", "Recorded.");
        const d = lab.launch || {};
        if (d.valid) return lab.V.pass({ valid: true, jti: d.claims && d.claims.jti }, "valid");
        return lab.V.notdev("After the reload the page has an expired token: nothing gives it a new one.", undefined, undefined, { valid: false, reason: d.reason });
      }, { resume: true })],
  });
  t("SA08", G.SA, "Back button inside the mini app", {
    goal: "Not specified: Back should go back inside the mini app while it has history, and close it at its first page.", spec: SPEC.methods,
    mustDo: "Not specified yet: let Back navigate the mini app's history first (WebView.canGoBack), and close the mini app only at its first page.",
    steps: [H("Open any test (so Bridge Lab has history), then press the phone's **Back** button."), Q("Did Back return to the previous screen inside Bridge Lab (not close it)?", { yes: "pass", no: "notdev", notdevText: "Back closes the mini app instead of going back in it." })],
  });
  t("SA09", G.SA, "Language and theme handed to the mini app", {
    goal: "Not specified: the mini app does not learn the user's language (en, am, om) or the app's theme.", spec: SPEC.launchApi,
    mustDo: "Not specified yet: tell the mini app the user's language and the theme (proposal: lang= and theme= on the launch url, or a bridge method).",
    steps: [A("Read what the page can see", "recorded", async (lab) => {
      const w = lab.win;
      return lab.V.info({ navigatorLanguage: w.navigator && w.navigator.language, languages: w.navigator && w.navigator.languages, prefersDark: !!(w.matchMedia && w.matchMedia("(prefers-color-scheme: dark)").matches), urlParams: Object.keys(lab.openedAt.query) }, "Recorded; nothing is specified.");
    })],
  });

  // ============================================================================================== Bridge error codes
  const rawId = () => "lab_raw_" + Math.random().toString(36).slice(2, 10);
  const rawStep = (label, build, code, matchAny) => A(label, 'error "' + code + '"', async (lab) => {
    const id = rawId();
    const msg = build(id);
    const r = await lab.raw(msg, 10000, matchAny ? undefined : id);
    return lab.expectRawError(r, code, lab.monitor);
  });
  t("E01", G.E, "invalid_message", {
    goal: 'A message that is not a JSON object, has no "adera": 1, a missing or too long id, or params over 1 KB is answered with invalid_message and nothing else happens.', spec: SPEC.errors,
    mustDo: "Validate every incoming message and answer invalid_message (with the id when there is one).",
    steps: [rawStep("Raw: not JSON", () => "this is not json", "invalid_message", true),
      rawStep("Raw: a JSON array", () => "[1, 2, 3]", "invalid_message", true),
      rawStep('Raw: no "adera": 1', (id) => ({ id, method: "getPermission", params: { capability: "user.basic" } }), "invalid_message"),
      rawStep("Raw: no id", () => ({ adera: 1, method: "getPermission", params: { capability: "user.basic" } }), "invalid_message", true),
      rawStep("Raw: id of 65 characters", () => ({ adera: 1, id: "lab_raw_" + "x".repeat(57), method: "getPermission", params: { capability: "user.basic" } }), "invalid_message", true),
      rawStep("Raw: params over 1 KB", (id) => ({ adera: 1, id, method: "getPermission", params: { capability: "user.basic", pad: "x".repeat(1100) } }), "invalid_message")],
  });
  t("E02", G.E, "unknown_method", {
    goal: "A method other than the four is answered with unknown_method.", spec: SPEC.errors, mustDo: "Answer unknown_method for any other method.",
    steps: [rawStep('Raw: method "deleteEverything"', (id) => ({ adera: 1, id, method: "deleteEverything", params: {} }), "unknown_method")],
  });
  t("E03", G.E, "invalid_params", {
    goal: "A capability that is not a key like location.fine is invalid_params (the API's 400 validation_failed).", spec: SPEC.errors, mustDo: "Reject a capability that is not a key with invalid_params.",
    steps: [A('adera.requestPermission("LOCATION FINE!")', "rejects invalid_params", expectReject("requestPermission", ["LOCATION FINE!"], "invalid_params")),
      A('adera.getPermission("")', "rejects invalid_params", expectReject("getPermission", [""], "invalid_params")),
      A("adera.requestPermission(42)", "rejects invalid_params", expectReject("requestPermission", [42], "invalid_params")),
      rawStep("Raw: requestPermission with no capability", (id) => ({ adera: 1, id, method: "requestPermission", params: {} }), "invalid_params")],
  });
  t("E04", G.E, "busy: a second request while a dialog is open", {
    goal: "One dialog at a time: a second requestPermission while a dialog is open is rejected with busy; no queue.", spec: SPEC.rules, mustDo: "Reject a second requestPermission with busy while a dialog is open.",
    steps: [pickAskable,
      A("requestPermission(<it>), and 2 s later the same again", "the second rejects busy", async (lab, ctx) => {
        const first = lab.call("requestPermission", [ctx.cap], DIALOG);
        await sleep(2000);
        const second = await lab.call("requestPermission", [ctx.cap], 10000);
        const v = lab.expectReject(second, "busy", lab.monitor);
        const one = await first;
        v.actual = { second: lab.show(second), first: lab.show(one) };
        return v;
      }, { timeout: DIALOG, hint: (lab, ctx) => "A dialog for " + capLabel(lab, ctx) + " opens. Leave it open **3 seconds**, then tap **Deny**." })],
  });
  t("E05", G.E, "dialog_dismissed: the dialog closed with Back", {
    goal: "A dialog dismissed without Allow or Deny answers nothing: the Promise rejects with dialog_dismissed and the state does not change.", spec: SPEC.methods, mustDo: "Reject with dialog_dismissed when the dialog is closed without an answer; send no answer to the server.",
    steps: [pickAskable,
      A("requestPermission(<it>)", "rejects dialog_dismissed", async (lab, ctx) => {
        ctx.before = await lab.state(ctx.cap);
        return lab.expectReject(await lab.call("requestPermission", [ctx.cap], DIALOG), "dialog_dismissed", lab.monitor);
      }, { timeout: DIALOG, hint: (lab, ctx) => "A dialog for " + capLabel(lab, ctx) + " opens. Close it with the phone's **Back** button (not Allow, not Deny)." }),
      A("getPermission(<it>)", "unchanged", async (lab, ctx) => { const s = await lab.state(ctx.cap); return s === ctx.before ? lab.V.pass(s, ctx.before) : lab.V.fail(s, ctx.before, "The state changed: an answer was sent."); })],
  });
  t("E06", G.E, "prompt_expired: answered after 5 minutes", {
    goal: "A prompt lives 5 minutes; answering later gives 409 prompt_expired -> the bridge rejects prompt_expired; the mini app may ask again.", spec: SPEC.errors, mustDo: "Map 409 prompt_expired to the bridge error prompt_expired.",
    steps: [pickAskable,
      A("requestPermission(<it>), answered after more than 5 minutes", "rejects prompt_expired", async (lab, ctx) => lab.expectReject(await lab.call("requestPermission", [ctx.cap], 420000), "prompt_expired", lab.monitor),
        { timeout: 420000, hint: (lab, ctx) => "A dialog for " + capLabel(lab, ctx) + " opens. Wait until the countdown shows **less than 1:30** (more than 5 minutes), then tap **Allow**." }),
      A("requestPermission(<it>) again", "a new dialog, then a state", async (lab, ctx) => lab.expectState(await lab.call("requestPermission", [ctx.cap], DIALOG), ["granted", "denied", "blocked"], lab.monitor), { timeout: DIALOG, hint: "A new dialog: answer it." })],
  });
  t("E07", G.E, "too_many_requests: 31 calls in a minute", {
    goal: "At most 30 requestPermission per minute per mini app (the gateway also allows 30 per minute); beyond that too_many_requests.", spec: SPEC.rules,
    mustDo: "Count requestPermission per mini app (30 per minute) and reject the excess with too_many_requests; map a gateway 429 to it too.",
    steps: [A('31 x adera.requestPermission("user.basic"), one after the other', "the 31st rejects too_many_requests", async (lab) => {
      const seq = [];
      for (let i = 0; i < 31; i++) {
        const o = await lab.call("requestPermission", ["user.basic"], 10000);
        const g = lab.gate(o, lab.monitor);
        if (g && i === 0) return g;
        seq.push(o.kind === "resolved" ? o.value : o.kind === "rejected" ? "x:" + o.error.code : o.kind);
        if (o.kind === "rejected" && o.error.code === "too_many_requests") {
          return i >= 29 ? lab.V.pass(seq, "too_many_requests after 30") : lab.V.pass(seq, "too_many_requests after 30", "Refused already at call " + (i + 1) + " (earlier calls in the last minute count too).");
        }
      }
      return lab.V.fail(seq, "too_many_requests at call 31", seq.some((s) => String(s).indexOf("x:") === 0) ? "Rejected with another code." : "All 31 answered: there is no limit.");
    }, { timeout: 120000 })],
  });
  t("E08", G.E, "Every error has a known code and a message, and no personal data", {
    goal: 'Errors are {"code", "message"} with codes from the table; internal_error and the others never carry details of the user or the phone.', spec: SPEC.errors,
    steps: [A("Check every error seen so far (API level and wire)", "known codes, a message, no phone/email/token/alias", async (lab) => {
      const errs = [];
      for (const e of lab.monitor.entries) {
        if (e.dir === "result" && e.payload && e.payload.rejected) errs.push({ code: e.payload.rejected.code, message: e.payload.rejected.message });
        if (e.dir === "in" && e.payload && e.payload.ok === false && e.payload.error) errs.push({ code: e.payload.error.code, message: e.payload.error.message });
      }
      if (!errs.length) return lab.V.info("none yet", "Run the other E tests first.");
      const codes = lab.BridgeMonitor ? lab.BridgeMonitor.ERROR_CODES : [];
      const bad = errs.filter((x) => (codes.length && !codes.includes(x.code)) || typeof x.message !== "string" || /\+?\d{9,}|@|u_p_[a-z0-9]{6,}|eyJ|Bearer/i.test(x.message || ""));
      return bad.length ? lab.V.fail(bad, "known codes, plain messages", "Unknown code, missing message, or the message carries personal data.") : lab.V.pass(errs.length + " errors, all fine", "known codes, plain messages");
    })],
  });
  t("E09", G.E, "internal_error", {
    goal: "internal_error covers anything else (5xx); a mini app cannot provoke it. E08 checks that no error leaks details.", spec: SPEC.errors,
    steps: [A("Nothing to provoke", "skipped", async (lab) => lab.V.skip("Cannot be provoked from the page."))],
  });

  // ============================================================================================== Origin rule
  t("O01", G.O, "The bridge is present on the registered origin", {
    goal: "window.adera exists on the origin of the registered entryUrl.", spec: SPEC.origin, mustDo: null,
    steps: [A("Compare this page's origin with the entryUrl and look for window.adera", "registered origin, bridge present", async (lab) => {
      const origin = lab.win.location.origin;
      const allowed = lab.config.allowedOrigins || [];
      const act = { origin, entryUrl: lab.config.entryUrl, allowedOrigins: allowed, adera: !!lab.win.adera };
      if (!allowed.includes(origin) && new URL(lab.config.entryUrl).origin !== origin) return lab.V.info(act, "This page is not on a registered origin (opened in another way?).");
      return lab.win.adera ? lab.V.pass(act, "bridge present") : lab.gate({ kind: "no-bridge" });
    })],
  });
  t("O02", G.O, "The bridge is present on another allowedOrigins origin", {
    goal: "allowedOrigins lists more origins the mini app may use (here http://localhost:8096); the bridge is injected there too.", spec: SPEC.origin,
    mustDo: "Inject window.adera for every origin in allowedOrigins, not only the entryUrl's.",
    steps: [H("With adb reverse, also run `adb reverse tcp:8096 tcp:8096`."), goTo("Open the probe page on the second allowed origin", (lab) => probeUrl(lab, lab.config.altUrl, "present")),
      probeResult("present", true)],
  });
  t("O03", G.O, "No bridge after navigating to a foreign origin", {
    goal: "Navigating to any other origin removes the bridge before the page loads; a foreign page never sees window.adera and gets no answers.", spec: SPEC.origin,
    mustDo: "Remove the bridge before a page of a non-registered origin loads; ignore its messages.",
    steps: [goTo("Open the probe page on a foreign origin (" + "127.0.0.1:8095)", (lab) => probeUrl(lab, lab.config.foreignUrl, "absent")), probeResult("absent", false)],
  });
  t("O04", G.O, "A foreign iframe gets no bridge and no answer", {
    goal: "A frame of another origin (an ad, say) inside the mini app: no window.adera there, and its messages are ignored with no answer.", spec: SPEC.origin,
    mustDo: "Check the origin of the frame that sent each message; ignore messages from frames whose origin is not registered.",
    steps: [A("Load the probe page from the foreign origin in an iframe here", "no window.adera in the frame, no answer", async (lab) => {
      const doc = lab.win.document;
      const url = probeUrl(lab, lab.config.foreignUrl, "absent", true);
      const got = new Promise((resolve) => {
        const on = (ev) => { if (ev.data && ev.data.labProbe) { lab.win.removeEventListener("message", on); resolve(ev.data.labProbe); } };
        lab.win.addEventListener("message", on);
        setTimeout(() => resolve(null), 15000);
      });
      const f = doc.createElement("iframe");
      f.src = url;
      f.style.cssText = "width:100%;height:90px;border:1px dashed #888";
      (doc.getElementById("frame-slot") || doc.body).appendChild(f);
      const p = await got;
      if (!p) return lab.V.fail("no report from the frame in 15 s", "a report", "The iframe did not load (is the foreign origin reachable?).");
      if (p.hasAdera) return lab.V.fail(p, "no window.adera", "The bridge was injected into a foreign frame.");
      if (p.answered) return lab.V.fail(p, "no answer", "The super app answered a message from a foreign frame.");
      return lab.V.pass(p, "no bridge, no answer", p.hasChannel ? "The AderaBridge channel is visible in the frame (WebView channels reach every frame), but its message got no answer: the super app checked the origin." : undefined);
    }, { timeout: 16000 })],
  });
  t("O05", G.O, "External links open outside the mini app", {
    goal: "Not specified for mini apps (the banner guide says external pages never open inside a mini-app WebView with the bridge).", spec: SPEC.origin,
    mustDo: "Not specified yet: open links to other sites (target=_blank, window.open) in the system browser or an in-app browser tab, never in the mini app's WebView with the bridge.",
    steps: [A("window.open(\"https://example.com/\", \"_blank\")", "opens outside", async (lab) => { const w = lab.win.open("https://example.com/", "_blank"); return lab.V.info({ windowReturned: !!w }, "Look at what happened."); }),
      Q("Did example.com open outside Bridge Lab (system browser or a browser tab), not inside the mini app?", { yes: "pass", no: "notdev", notdevText: "External links open inside the mini app's WebView." })],
  });

  return { tests: T, groups: Object.values(G), SPEC };
});
