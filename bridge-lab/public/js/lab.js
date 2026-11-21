// The lab engine: results store (localStorage), backend client, verdict helpers, step runner, report.
// Browser: window.Lab. Node (unit tests): module.exports.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.Lab = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const STATUS = {
    notrun: { label: "Not run", icon: "○" },
    pass: { label: "Pass", icon: "✅" },
    fail: { label: "Fail", icon: "❌" },
    notdev: { label: "Not developed yet", icon: "🚧" },
    skip: { label: "Skipped", icon: "⏭️" },
  };
  const VERDICT = {
    pass: { label: "pass", icon: "✅" },
    fail: { label: "fail", icon: "❌" },
    notdev: { label: "not developed", icon: "🚧" },
    info: { label: "info", icon: "ℹ️" },
    skip: { label: "skipped", icon: "⏭️" },
    running: { label: "running", icon: "⏳" },
  };

  // ---------------------------------------------------------------------------------------------- storage (never throws)
  function createStore(storage, key) {
    key = key || "bridgeLab.v1";
    const mem = {};
    function read(k) {
      try { const v = storage && storage.getItem(key + "." + k); return v ? JSON.parse(v) : null; } catch { return mem[k] || null; }
    }
    function write(k, v) {
      mem[k] = v;
      try { if (storage) storage.setItem(key + "." + k, JSON.stringify(v)); } catch { /* full or blocked: memory only */ }
    }
    function remove(k) {
      delete mem[k];
      try { if (storage) storage.removeItem(key + "." + k); } catch { /* ignore */ }
    }
    return { read, write, remove };
  }

  // ---------------------------------------------------------------------------------------------- verdicts
  const V = {
    pass: (actual, expected, note) => ({ verdict: "pass", actual, expected, note }),
    fail: (actual, expected, note) => ({ verdict: "fail", actual, expected, note }),
    info: (actual, note, expected) => ({ verdict: "info", actual, expected, note }),
    skip: (note, actual) => ({ verdict: "skip", actual, note }),
    notdev: (reason, mustDo, spec, actual) => ({ verdict: "notdev", actual: actual === undefined ? reason : actual, note: reason, mustDo, spec }),
  };

  // what the super app must do when a whole layer is missing
  const MUST = {
    bridge: 'Inject window.adera into the mini app\'s page, only for pages whose origin is the registered entryUrl\'s origin or one of allowedOrigins. It has four methods (requestPermission, getPermission, openPermissionSettings, getLocation), each returning a Promise. Transport in the Flutter WebView: the page posts JSON strings {"adera":1,"id","method","params"} to the JavaScript channel AderaBridge; the super app answers by calling window.adera._receive(json).',
    answer: 'Answer every request: call window.adera._receive with {"adera":1,"id":<the same id>,"ok":true,"result":...} or {"adera":1,"id":<the same id>,"ok":false,"error":{"code":"<code from the table>","message":"..."}}. The Promise of the method must settle.',
    requestPermission: 'adera.requestPermission(capability): call POST /api/v1/me/apps/{app}/permissions/{capability}/request. If the answer has a prompt, show the dialog with prompt.label and prompt.purpose verbatim and Allow / Deny, call PUT .../answer with the promptId and resolve with the new state ("granted", "denied", "blocked"); with no prompt resolve at once ("granted", "blocked", "not_available"). Back button: reject with dialog_dismissed.',
    getPermission: 'adera.getPermission(capability): never show a dialog. Call GET /api/v1/me/apps/{app}/permissions and resolve with the capability\'s state in lower case ("not_asked", "granted", "denied", "blocked"); a capability not in the list resolves "not_available".',
    openPermissionSettings: 'adera.openPermissionSettings(): open this app\'s permission sheet from the super app menu (one switch per permission from GET .../permissions; a switch calls PUT .../permissions/{capability}; the only way out of blocked) and resolve with {"permissions": {"<capability>": "<state>", ...}} when the user closes it.',
    getLocation: 'adera.getLocation(options?): never show our dialog. Check the consent on the server (GET .../permissions), then read the phone. precision "fine" (default) needs location.fine and resolves {"state":"granted","location":{"latitude","longitude","accuracyMeters","at"}}; "coarse" needs location.coarse and resolves {"state":"granted","place":{"countryCode","region","city"}} with no coordinates. Otherwise {"state":"denied"|"blocked"|"not_asked"|"not_available"}, never saying whether our consent or the phone refused.',
    launch: "When the user opens a mini app: call POST /api/v1/me/apps/{app}/launch with the user's session and load the returned url (the registered entryUrl with ?launch=<token> or &launch=<token>) in the WebView. Show no dialog at launch.",
  };
  const SPEC_FOR = {
    bridge: "app-permissions/bridge-spec.html#bridge-methods", answer: "app-permissions/bridge-spec.html#bridge-format",
    requestPermission: "app-permissions/bridge-spec.html#bridge-methods", getPermission: "app-permissions/bridge-spec.html#bridge-methods",
    openPermissionSettings: "app-permissions/bridge-spec.html#bridge-methods", getLocation: "app-permissions/bridge-spec.html#bridge-methods",
    launch: "app-permissions/super-app-api.html#ep-launch",
  };

  // classify a bridge outcome that is not an answer (no bridge, no method, no reply, threw...) into a verdict, or null
  function gate(o, monitor) {
    if (!o) return V.fail("no outcome", "an answer");
    if (o.kind === "no-bridge") return V.notdev("window.adera is not injected: the bridge is not built (or not for this origin).", MUST.bridge, SPEC_FOR.bridge, "window.adera is undefined");
    if (o.kind === "no-method") return V.notdev("adera." + o.method + " does not exist: this method is not built.", MUST[o.method] || MUST.bridge, SPEC_FOR[o.method] || SPEC_FOR.bridge, "typeof window.adera." + o.method + " = undefined");
    if (o.kind === "threw") return V.fail({ threw: o.error }, "a Promise", "The method threw instead of returning a Promise that settles.");
    if (o.kind === "not-promise") return V.fail({ returned: o.value }, "a Promise", "The method did not return a Promise.");
    if (o.kind === "no-channel") return V.notdev("Neither the AderaBridge channel nor a parent frame exists, so a raw message cannot be sent.", MUST.bridge, SPEC_FOR.answer, "window.AderaBridge is undefined");
    if (o.kind === "timeout") {
      const stray = monitor ? monitor.entries.filter((e) => e.dir === "in" && e.unsolicited).slice(-3) : [];
      if (stray.length) return V.fail({ timeout: Math.round(o.ms / 1000) + " s", strayAnswers: stray.map((e) => e.payload) }, "an answer with the request's id",
        "An answer arrived but its id matches no request (id mismatch), so the Promise never settled.");
      return V.notdev("The super app did not answer within " + Math.round(o.ms / 1000) + " s.", MUST.answer + " " + (MUST[o.method] || ""), SPEC_FOR[o.method] || SPEC_FOR.answer, "no answer after " + Math.round(o.ms / 1000) + " s");
    }
    return null;
  }

  function show(o) {
    if (!o) return o;
    if (o.kind === "resolved") return { resolved: o.value, ms: o.ms };
    if (o.kind === "rejected") return { rejected: { code: o.error.code, message: o.error.message, shape: o.error.shape }, ms: o.ms };
    return o;
  }

  // resolved with one of the wanted values (strings) and no spec violation
  function expectState(o, wanted, monitor, note) {
    const g = gate(o, monitor);
    if (g) return g;
    const exp = wanted.map((w) => JSON.stringify(w)).join(" or ");
    if (o.kind === "rejected") return V.fail(show(o), exp, "Rejected with " + o.error.code + (note ? ". " + note : ""));
    if (o.violations && o.violations.length) return V.fail(show(o), exp, "Breaks the spec: " + o.violations.join("; "));
    if (!wanted.includes(o.value)) return V.fail(show(o), exp, note);
    return V.pass(show(o), exp, note);
  }

  function expectReject(o, code, monitor, note) {
    const g = gate(o, monitor);
    if (g) {
      if (o && o.kind === "timeout") return V.fail(show(o), "rejects with " + code, "No answer at all; the spec wants the error " + code + ". " + MUST.answer);
      return g;
    }
    if (o.kind === "resolved") return V.fail(show(o), "rejects with " + code, "Resolved instead of rejecting" + (note ? ". " + note : ""));
    if (o.error.code !== code) return V.fail(show(o), "rejects with " + code, "Rejected with a different code" + (note ? ". " + note : ""));
    if (!o.error.message) return V.pass(show(o), "rejects with " + code, "Code right; the error has no message text (the spec has code and message).");
    return V.pass(show(o), "rejects with " + code, note);
  }

  // a raw wire answer must be an error with this code
  function expectRawError(r, code, monitor) {
    const g = gate(r, monitor);
    if (g && r.kind !== "timeout") return g;
    if (r.kind === "timeout") return V.fail("no answer within " + Math.round(r.ms / 1000) + " s", 'error "' + code + '"', 'The spec: a bad message "is answered with an error and nothing else happens". ' + MUST.answer);
    const a = r.answer || {};
    const got = a.error && a.error.code;
    const v = (r.entry && r.entry.violations) || [];
    if (a.ok === false && got === code) return v.length ? V.fail(a, 'ok false, error.code "' + code + '"', "Right code but: " + v.join("; ")) : V.pass(a, 'ok false, error.code "' + code + '"');
    return V.fail(a, 'ok false, error.code "' + code + '"', got ? "Answered with " + got : "Not an error answer");
  }

  // ---------------------------------------------------------------------------------------------- the lab
  function createLab(env) {
    // env: { win, storage, fetch, monitor, tests, config, now }
    const win = env.win;
    const store = createStore(env.storage);
    const monitor = env.monitor;
    const fetchImpl = env.fetch;
    let sessionId = store.read("session");
    if (!sessionId) {
      sessionId = "s" + Math.random().toString(36).slice(2, 12) + Date.now().toString(36);
      store.write("session", sessionId);
    }
    const lab = {
      store, monitor, V, MUST, SPEC_FOR, gate, show, expectState, expectReject, expectRawError,
      config: env.config || {}, sessionId, launch: null, openedAt: null, ctx: {},
      tests: env.tests || [], win, BridgeMonitor: env.BridgeMonitor,
    };

    // ---- backend
    lab.api = async function (method, path, body) {
      try {
        const res = await fetchImpl(path, { method, headers: { "Content-Type": "application/json", "X-Lab-Session": sessionId }, body: body === undefined ? undefined : JSON.stringify(body) });
        const text = await res.text();
        let data;
        try { data = text ? JSON.parse(text) : null; } catch { data = { _raw: text.slice(0, 300) }; }
        return { status: res.status, data };
      } catch (e) {
        return { status: 0, data: { error: "network", message: e.message } };
      }
    };
    lab.profile = (fields, alias, mode) => lab.api("POST", "/api/partner/profile", { fields, alias, mode });
    lab.notify = (o) => lab.api("POST", "/api/partner/notify", o);
    lab.note = (text) => lab.api("POST", "/api/client-note", { text });

    // ---- callbacks
    lab.callbackSeq = async function () {
      const r = await lab.api("GET", "/api/callbacks?since=999999999");
      return (r.data && r.data.last) || 0;
    };
    lab.callbacks = async function (since) {
      const r = await lab.api("GET", "/api/callbacks?since=" + (since || 0));
      return (r.data && r.data.callbacks) || [];
    };
    lab.waitCallback = async function (match, timeoutMs, since) {
      const end = Date.now() + (timeoutMs || 20000);
      const ok = (c) => Object.entries(match).every(([k, v]) => (k === "event" ? c.event === v : c.body && c.body[k] === v));
      while (Date.now() < end) {
        const list = (await lab.callbacks(since || 0)).filter((c) => !c.selftest);
        const hit = list.find(ok);
        if (hit) return hit;
        await sleep(1500);
      }
      return null;
    };
    lab.expectCallback = async function (match, timeoutMs, since) {
      const hit = await lab.waitCallback(match, timeoutMs, since);
      const exp = "signed callback " + JSON.stringify(match);
      if (!hit) {
        if (!lab.config.callbackUrl) return V.fail("no callback URL configured", exp, "Run seed.py (it sets the callback URL).");
        return V.fail("none within " + Math.round((timeoutMs || 20000) / 1000) + " s", exp, "Adera did not send it, or the change did not happen (did the super app call the user API?). The Backend log shows every callback received.");
      }
      if (!hit.verified) return V.fail({ event: hit.event, body: hit.body, signature: hit.detail }, exp, "Arrived but the signature did not verify.");
      return V.pass({ event: hit.event, body: hit.body, idempotencyKey: hit.idempotencyKey, signature: "verified" }, exp);
    };

    // ---- bridge shortcuts
    lab.call = (method, args, timeoutMs) => monitor.invoke(method, args || [], timeoutMs || 10000);
    lab.state = async function (cap, timeoutMs) {
      const o = await lab.call("getPermission", [cap], timeoutMs);
      return o.kind === "resolved" ? o.value : null;
    };
    lab.raw = (msg, timeoutMs, matchId) => monitor.rawSend(msg, timeoutMs, matchId);
    lab.capabilities = () => (lab.config.capabilities || []).map((c) => c.key);
    lab.capability = (key) => (lab.config.capabilities || []).find((c) => c.key === key) || { key, label: "?", purpose: "?" };
    lab.specUrl = (ref) => (lab.config.docsUrl || "/docs") + "/" + ref;

    // ---- results
    lab.results = () => store.read("results") || {};
    lab.result = (id) => lab.results()[id] || { status: "notrun", steps: {} };
    lab.saveResult = function (id, patch) {
      const all = lab.results();
      const cur = all[id] || { status: "notrun", steps: {} };
      all[id] = Object.assign({}, cur, patch, { at: new Date().toISOString() });
      store.write("results", all);
      return all[id];
    };
    lab.saveStep = function (id, index, verdict) {
      const r = lab.result(id);
      r.steps = r.steps || {};
      r.steps[index] = Object.assign({}, verdict, { at: new Date().toISOString() });
      lab.saveResult(id, { steps: r.steps });
    };
    lab.reset = function (id) {
      if (id) {
        const all = lab.results();
        delete all[id];
        store.write("results", all);
        delete lab.ctx[id];
        store.remove("ctx." + id);
      } else {
        for (const t of lab.tests) store.remove("ctx." + t.id);
        lab.ctx = {};
        store.write("results", {});
      }
    };

    // ---- the proposed verdict of a test from its steps
    lab.suggest = function (test) {
      const r = lab.result(test.id);
      const vs = test.steps.map((s, i) => r.steps && r.steps[i] && r.steps[i].verdict).filter(Boolean);
      if (!vs.length) return null;
      if (vs.includes("fail")) return "fail";
      if (vs.includes("notdev")) return "notdev";
      const judged = test.steps.filter((s) => s.kind !== "human").length;
      if (vs.filter((v) => v === "pass").length && vs.length >= judged) return "pass";
      if (vs.every((v) => v === "skip")) return "skip";
      return null;
    };

    // ---- run one step
    lab.runStep = async function (test, index, extra) {
      const step = test.steps[index];
      const ctx = lab.context(test.id);
      if (step.mark) ctx.mark = await lab.callbackSeq();
      let v;
      try {
        if (step.kind === "question") {
          const which = extra === "yes" ? step.yes : step.no;
          v = which === "notdev" ? V.notdev(step.notdevText || "The super app does not do this yet.", step.mustDo || test.mustDo, test.spec, extra)
            : { verdict: which, actual: extra, expected: step.expect };
        } else v = await step.run(lab, ctx, test);
      } catch (e) {
        v = V.fail({ error: String(e && e.message || e) }, step.expect, "Bridge Lab itself failed running this step (a bug in the lab, not the super app).");
      }
      if (!v || !v.verdict) v = V.info(v, "no verdict");
      if (v.verdict === "notdev" && !v.mustDo) v.mustDo = test.mustDo;
      if (v.verdict === "notdev" && !v.spec) v.spec = test.spec;
      v.expected = v.expected === undefined ? step.expect : v.expected;
      lab.saveStep(test.id, index, v);
      lab.saveContext(test.id);
      return v;
    };

    // per-test scratch (chosen capability, externalId, callback mark...), kept across reloads and navigation
    lab.context = function (id) {
      if (!lab.ctx[id]) lab.ctx[id] = store.read("ctx." + id) || { mark: 0 };
      return lab.ctx[id];
    };
    lab.saveContext = function (id) {
      if (lab.ctx[id]) store.write("ctx." + id, lab.ctx[id]);
    };

    // ---- report
    lab.report = function () {
      const results = lab.results();
      const groups = [];
      for (const t of lab.tests) {
        let g = groups.find((x) => x.name === t.group);
        if (!g) groups.push((g = { name: t.group, tests: [] }));
        const r = results[t.id] || { status: "notrun", steps: {} };
        g.tests.push({ id: t.id, title: t.title, status: r.status || "notrun", note: r.note || "", at: r.at || null, steps: r.steps || {} });
      }
      const count = (s) => lab.tests.filter((t) => ((results[t.id] || {}).status || "notrun") === s).length;
      const todo = [];
      for (const t of lab.tests) {
        const r = results[t.id];
        if (!r) continue;
        const notdevSteps = Object.values(r.steps || {}).filter((s) => s.verdict === "notdev");
        if (r.status === "notdev" || notdevSteps.length) {
          const musts = [...new Set(notdevSteps.map((s) => s.mustDo).filter(Boolean))];
          todo.push({ id: t.id, title: t.title, mustDo: musts.length ? musts : [t.mustDo].filter(Boolean), spec: t.spec, note: r.note || "" });
        }
      }
      return {
        generatedAt: new Date().toISOString(), app: { appId: lab.config.appId, slug: lab.config.slug, env: lab.config.env },
        user: lab.launch && lab.launch.claims ? { alias: lab.launch.claims.sub } : null, userAgent: win && win.navigator ? win.navigator.userAgent : "",
        counts: { total: lab.tests.length, pass: count("pass"), fail: count("fail"), notdev: count("notdev"), skip: count("skip"), notrun: count("notrun") },
        groups, superAppTodo: todo,
      };
    };

    lab.reportMarkdown = function () {
      const r = lab.report();
      const L = [];
      L.push("# Bridge Lab report", "", "Generated " + r.generatedAt + (r.user ? " for user " + r.user.alias : "") + ", app " + (r.app.slug || "?") + " (" + (r.app.env || "?") + ")", "");
      L.push(`Total ${r.counts.total}: pass ${r.counts.pass}, fail ${r.counts.fail}, not developed ${r.counts.notdev}, skipped ${r.counts.skip}, not run ${r.counts.notrun}`, "");
      L.push("## What the super app still needs to build", "");
      if (!r.superAppTodo.length) L.push("Nothing recorded yet.", "");
      for (const t of r.superAppTodo) {
        L.push(`- **${t.id} ${t.title}**${t.note ? " (note: " + t.note + ")" : ""}`);
        for (const m of t.mustDo) L.push("  - " + m);
        if (t.spec) L.push("  - spec: docs/guides/" + t.spec);
      }
      L.push("");
      for (const g of r.groups) {
        L.push("## " + g.name, "", "| Id | Test | Status | Note |", "|---|---|---|---|");
        for (const t of g.tests) L.push(`| ${t.id} | ${t.title.replace(/\|/g, "/")} | ${STATUS[t.status].icon} ${STATUS[t.status].label} | ${(t.note || "").replace(/\|/g, "/").replace(/\n/g, " ")} |`);
        L.push("");
      }
      return L.join("\n");
    };

    return lab;
  }

  function sleep(ms) {
    return new Promise((r) => setTimeout(r, ms));
  }

  return { createLab, createStore, V, STATUS, VERDICT, MUST, gate, expectState, expectReject, expectRawError, sleep };
});
