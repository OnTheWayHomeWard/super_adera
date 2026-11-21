// Bridge monitor: finds window.adera, wraps the wire (AderaBridge.postMessage out, window.adera._receive in) to log every
// message in both directions, validates each one against the bridge specification
// (docs/guides/app-permissions/bridge-spec.html) and offers invoke() (API level) and rawSend() (wire level).
// Works in the browser (window.BridgeMonitor) and in node (module.exports) for the unit tests.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BridgeMonitor = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const METHODS = ["requestPermission", "getPermission", "openPermissionSettings", "getLocation"];
  const REQUEST_STATES = ["granted", "denied", "blocked", "not_available"];
  const GET_STATES = ["granted", "denied", "blocked", "not_asked", "not_available"];
  const LOCATION_STATES = ["granted", "denied", "blocked", "not_asked", "not_available"];
  const ERROR_CODES = ["invalid_message", "unknown_method", "invalid_params", "busy", "dialog_dismissed", "prompt_expired", "app_not_live",
    "session_expired", "account_not_active", "too_many_requests", "service_unavailable", "internal_error"];
  const CAPABILITY = /^[a-z][a-z0-9_.]{1,38}$/;

  function now() {
    return typeof performance !== "undefined" && performance.now ? performance.now() : Date.now();
  }

  function parse(x) {
    if (typeof x !== "string") return { value: x, wasString: false };
    try {
      return { value: JSON.parse(x), wasString: true };
    } catch (e) {
      return { value: x, wasString: true, parseError: e.message };
    }
  }

  const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

  // ---- validation of one request on the wire (page -> super app)
  function validateRequest(raw) {
    const v = [];
    const p = parse(raw);
    if (!p.wasString) v.push("sent as a " + typeof raw + ", the spec posts a JSON string to AderaBridge");
    if (p.parseError) return { msg: null, violations: v.concat("not JSON: " + p.parseError) };
    const m = p.value;
    if (!isObj(m)) return { msg: m, violations: v.concat("not a JSON object") };
    if (m.adera !== 1) v.push('no "adera": 1');
    if (typeof m.id !== "string" || !m.id || m.id.length > 64) v.push("id must be a string of 1 to 64 characters");
    if (!METHODS.includes(m.method)) v.push("unknown method " + JSON.stringify(m.method));
    if (m.params !== undefined && !isObj(m.params)) v.push("params must be an object");
    if (m.params !== undefined && JSON.stringify(m.params).length > 1024) v.push("params over 1 KB");
    return { msg: m, violations: v };
  }

  // ---- validation of the result of one method (the "result" of an ok answer, or what the Promise resolved with)
  function validateResult(method, result, level) {
    const v = [];
    const wire = level === "wire";
    if (method === "requestPermission" || method === "getPermission") {
      const allowed = method === "requestPermission" ? REQUEST_STATES : GET_STATES;
      const state = wire ? (isObj(result) ? result.state : undefined) : result;
      if (wire && !isObj(result)) v.push('result must be {"state": ...}');
      if (!allowed.includes(state)) v.push("state " + JSON.stringify(state) + " is not one of " + allowed.join(", "));
    } else if (method === "openPermissionSettings") {
      if (!isObj(result) || !isObj(result.permissions)) v.push('must be {"permissions": {"<capability>": "<state>"}}');
      else for (const [k, s] of Object.entries(result.permissions)) {
        if (!CAPABILITY.test(k)) v.push("key " + JSON.stringify(k) + " is not a capability");
        if (!GET_STATES.includes(s)) v.push(k + ": state " + JSON.stringify(s) + " unknown");
      }
    } else if (method === "getLocation") {
      if (!isObj(result)) v.push('must be {"state": ...}');
      else {
        if (!LOCATION_STATES.includes(result.state)) v.push("state " + JSON.stringify(result.state) + " unknown");
        if (result.state === "granted") v.push(...validateReading(result));
        else if (result.location || result.place) v.push("a reading came with state " + result.state);
      }
    }
    return v;
  }

  function validateReading(r) {
    const v = [];
    if (r.location !== undefined) {
      const l = r.location;
      if (!isObj(l)) return ["location must be an object"];
      if (typeof l.latitude !== "number" || l.latitude < -90 || l.latitude > 90) v.push("location.latitude must be a number from -90 to 90");
      if (typeof l.longitude !== "number" || l.longitude < -180 || l.longitude > 180) v.push("location.longitude must be a number from -180 to 180");
      if (typeof l.accuracyMeters !== "number" || l.accuracyMeters < 0) v.push("location.accuracyMeters must be a number >= 0");
      if (typeof l.at !== "string" || isNaN(Date.parse(l.at))) v.push("location.at must be an ISO time");
      if (r.place !== undefined) v.push("fine precision returns location only, not place");
    } else if (r.place !== undefined) {
      const p = r.place;
      if (!isObj(p)) return ["place must be an object"];
      if (typeof p.countryCode !== "string" || !/^[A-Z]{2}$/.test(p.countryCode)) v.push("place.countryCode must be two capital letters");
      if (typeof p.city !== "string") v.push("place.city must be text");
      if (typeof p.region !== "string") v.push("place.region must be text");
      if (/latitude|longitude/i.test(JSON.stringify(r))) v.push("coarse precision must never contain coordinates");
    } else {
      v.push('state "granted" without location or place');
    }
    return v;
  }

  // ---- validation of one answer on the wire (super app -> page)
  function validateAnswer(raw, pending) {
    const v = [];
    const p = parse(raw);
    if (p.parseError) return { msg: null, violations: ["not JSON: " + p.parseError] };
    const m = p.value;
    if (!isObj(m)) return { msg: m, violations: ["not a JSON object"] };
    if (m.adera !== 1) v.push('no "adera": 1');
    const req = m.id != null ? pending.get(String(m.id)) : undefined;
    if (typeof m.ok !== "boolean") v.push('"ok" must be true or false');
    if (m.ok === true && !("result" in m)) v.push('ok true without "result"');
    if (m.ok === false) {
      if (!isObj(m.error)) v.push('ok false without an "error" object');
      else {
        if (!ERROR_CODES.includes(m.error.code)) v.push("error.code " + JSON.stringify(m.error.code) + " is not in the spec's table");
        if (typeof m.error.message !== "string") v.push("error.message must be text");
      }
    }
    if (m.ok === true && req && req.method) v.push(...validateResult(req.method, m.result, "wire"));
    return { msg: m, violations: v, request: req };
  }

  // the rejection reason of a Promise, whatever its shape (Error with code, {code, message}, a string)
  function normaliseError(e) {
    if (e == null) return { code: null, message: null, shape: String(e) };
    if (typeof e === "string") return { code: e, message: null, shape: "string" };
    if (isObj(e) || e instanceof Error) {
      const code = e.code || (isObj(e.error) ? e.error.code : undefined) || null;
      const message = e.message || (isObj(e.error) ? e.error.message : undefined) || null;
      return { code, message, shape: e instanceof Error ? "Error" : "object", raw: safe(e) };
    }
    return { code: null, message: String(e), shape: typeof e };
  }

  function safe(x) {
    try { return JSON.parse(JSON.stringify(x, (k, v) => (v instanceof Error ? { name: v.name, message: v.message, code: v.code } : v))); } catch { return String(x); }
  }

  // ------------------------------------------------------------------------------------------------ the monitor
  function createMonitor(win, opts) {
    opts = opts || {};
    const entries = [];
    const listeners = new Set();
    const pending = new Map(); // wire id -> { method, t0, entry }
    const anonymous = []; // requests without a usable id (not JSON, an array, no id): an answer with no id answers the oldest
    const waiters = new Set(); // raw-send waiters
    let seq = 0;
    let idCounter = 0;
    const state = { injected: false, injectedAt: null, transport: "none", wiredOut: false, wiredIn: false, frozen: false, notes: [] };

    function add(e) {
      e.seq = ++seq;
      e.at = new Date().toISOString();
      entries.push(e);
      if (entries.length > (opts.max || 2000)) entries.splice(0, entries.length - (opts.max || 2000));
      listeners.forEach((fn) => { try { fn(e); } catch { /* ignore */ } });
      return e;
    }

    function onOutgoing(raw, via) {
      const { msg, violations } = validateRequest(raw);
      const e = add({ dir: "out", via, method: msg && msg.method, id: msg && msg.id, payload: msg != null ? msg : raw, violations });
      if (msg && typeof msg === "object" && msg.id != null) pending.set(String(msg.id), { method: msg.method, t0: now(), entry: e });
      else anonymous.push({ method: msg && msg.method, t0: now(), entry: e });
      return e;
    }

    function onIncoming(raw, via) {
      const checked = validateAnswer(raw, pending);
      const { msg, violations } = checked;
      let request = checked.request;
      if (!request && msg && typeof msg === "object" && msg.id == null && anonymous.length) {
        request = anonymous.shift();
      }
      const e = add({ dir: "in", via, id: msg && msg.id, method: request ? request.method : undefined, payload: msg != null ? msg : raw, violations });
      if (request) {
        e.ms = Math.round(now() - request.t0);
        if (msg.id != null) pending.delete(String(msg.id));
        request.entry.answered = true;
      } else if (!state.wiredOut && !(msg && msg.id != null && String(msg.id).indexOf("lab_raw_") === 0)) {
        e.note = "request not captured (the outgoing channel could not be wrapped), so the id cannot be matched";
      } else {
        e.unsolicited = true;
        e.violations.push("unsolicited: no request with this id is waiting" + (msg && msg.id != null ? " (id " + JSON.stringify(msg.id) + ")" : ""));
      }
      waiters.forEach((w) => w(e));
      return e;
    }

    // ---- wrapping (re-done periodically: the super app may inject late or replace the object)
    function wrap() {
      const a = win.adera;
      const ch = win.AderaBridge;
      const had = state.injected;
      state.injected = !!a && (typeof a === "object" || typeof a === "function");
      if (state.injected && !had) {
        state.injectedAt = new Date().toISOString();
        add({ dir: "note", text: "window.adera found (methods: " + METHODS.filter((m) => typeof a[m] === "function").join(", ") + (typeof a._receive === "function" ? ", _receive" : "") + ")" });
      }
      if (ch && typeof ch.postMessage === "function" && !ch.postMessage.__lab) {
        const orig = ch.postMessage;
        const wrapped = function (s) { onOutgoing(s, "AderaBridge"); return orig.apply(ch, arguments); };
        wrapped.__lab = true;
        wrapped.__orig = orig;
        try { ch.postMessage = wrapped; } catch { /* host object */ }
        state.wiredOut = ch.postMessage === wrapped;
        if (!state.wiredOut && !state.notes.includes("out")) { state.notes.push("out"); add({ dir: "note", text: "AderaBridge.postMessage cannot be wrapped (host object): requests are logged at API level only" }); }
      }
      if (a && typeof a._receive === "function" && !a._receive.__lab) {
        const orig = a._receive;
        const wrapped = function (json) { onIncoming(json, "_receive"); return orig.apply(a, arguments); };
        wrapped.__lab = true;
        wrapped.__orig = orig;
        try { a._receive = wrapped; } catch { /* frozen */ }
        state.wiredIn = a._receive === wrapped;
        if (!state.wiredIn && !state.notes.includes("in")) { state.notes.push("in"); state.frozen = true; add({ dir: "note", text: "window.adera._receive cannot be wrapped (object frozen): answers are logged at API level only" }); }
      }
      state.transport = ch && typeof ch.postMessage === "function" ? "AderaBridge channel" : win.parent && win.parent !== win ? "iframe (postMessage to parent)" : state.injected ? "unknown (no AderaBridge channel)" : "none";
      return state;
    }

    // web-hosted transport: answers arrive as message events from the parent
    if (win.addEventListener && win.parent && win.parent !== win) {
      win.addEventListener("message", (ev) => {
        const d = ev.data;
        const looks = (typeof d === "string" && d.indexOf('"adera"') >= 0) || (isObj(d) && "adera" in d);
        if (looks && ev.source === win.parent) onIncoming(d, "message event from " + ev.origin);
      });
    }

    // ---- API level: call one method and classify what happened
    function invoke(method, args, timeoutMs) {
      timeoutMs = timeoutMs || 10000;
      wrap();
      const a = win.adera;
      if (!state.injected) return Promise.resolve({ kind: "no-bridge", method, args });
      if (typeof a[method] !== "function") return Promise.resolve({ kind: "no-method", method, args });
      const t0 = now();
      const call = add({ dir: "call", method, args: safe(args), payload: { method, args: safe(args) }, violations: [] });
      let p;
      try {
        p = a[method].apply(a, args);
      } catch (e) {
        const err = normaliseError(e);
        call.violations.push("threw synchronously instead of returning a Promise");
        add({ dir: "result", method, ms: Math.round(now() - t0), payload: { threw: err }, violations: ["threw synchronously"] });
        return Promise.resolve({ kind: "threw", method, args, error: err });
      }
      if (!p || typeof p.then !== "function") {
        call.violations.push("did not return a Promise");
        return Promise.resolve({ kind: "not-promise", method, args, value: safe(p) });
      }
      return new Promise((resolve) => {
        let done = false;
        const timer = setTimeout(() => {
          if (done) return;
          done = true;
          add({ dir: "result", method, ms: timeoutMs, payload: { timeout: true }, violations: ["no answer within " + Math.round(timeoutMs / 1000) + " s"] });
          resolve({ kind: "timeout", method, args, ms: timeoutMs });
        }, timeoutMs);
        p.then((value) => {
          const ms = Math.round(now() - t0);
          const v = validateResult(method, value, "api");
          add({ dir: "result", method, ms, payload: { resolved: safe(value) }, violations: v, late: done });
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolve({ kind: "resolved", method, args, value, ms, violations: v });
        }, (e) => {
          const ms = Math.round(now() - t0);
          const err = normaliseError(e);
          const v = [];
          if (!ERROR_CODES.includes(err.code)) v.push("rejected with code " + JSON.stringify(err.code) + ", not a code of the spec's table");
          add({ dir: "result", method, ms, payload: { rejected: err }, violations: v, late: done });
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolve({ kind: "rejected", method, args, error: err, ms, violations: v });
        });
      });
    }

    // ---- wire level: send a raw message (string or object) straight to the transport and wait for an answer
    function rawSend(message, timeoutMs, matchId) {
      timeoutMs = timeoutMs || 10000;
      wrap();
      const ch = win.AderaBridge;
      const str = typeof message === "string" ? message : JSON.stringify(message);
      let send;
      if (ch && typeof ch.postMessage === "function") {
        const orig = ch.postMessage.__orig || ch.postMessage;
        send = () => orig.call(ch, str);
      } else if (win.parent && win.parent !== win) {
        send = () => win.parent.postMessage(str, "*");
      } else {
        return Promise.resolve({ kind: "no-channel" });
      }
      onOutgoing(str, "raw (Bridge Lab test)");
      const t0 = now();
      return new Promise((resolve) => {
        let finished = false;
        const finish = (r) => { if (finished) return; finished = true; waiters.delete(w); clearTimeout(timer); resolve(r); };
        const w = (e) => {
          const id = e.payload && e.payload.id;
          if (matchId === undefined || matchId === null ? true : String(id) === String(matchId)) finish({ kind: "answer", entry: e, answer: e.payload, ms: Math.round(now() - t0) });
        };
        waiters.add(w);
        const timer = setTimeout(() => finish({ kind: "timeout", ms: timeoutMs }), timeoutMs);
        try { send(); } catch (err) { finish({ kind: "threw", error: normaliseError(err) }); }
      });
    }

    function nextId(prefix) {
      idCounter += 1;
      return (prefix || "lab") + "_" + Date.now().toString(36) + "_" + idCounter;
    }

    return {
      wrap, invoke, rawSend, nextId, state, entries,
      onEntry: (fn) => { listeners.add(fn); return () => listeners.delete(fn); },
      clear: () => entries.splice(0, entries.length),
      // the unit tests feed messages through these as if the wire carried them
      _onOutgoing: onOutgoing, _onIncoming: onIncoming,
    };
  }

  return { createMonitor, validateRequest, validateAnswer, validateResult, normaliseError, METHODS, REQUEST_STATES, GET_STATES, ERROR_CODES, CAPABILITY };
});
