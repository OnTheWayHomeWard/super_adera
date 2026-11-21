// A FAKE super-app bridge for the tests ONLY (never served to the phone): window.AderaBridge (the channel) and window.adera
// (four Promise methods + _receive), following docs/guides/app-permissions/bridge-spec.html, with switches to break it.
// installStub(win, { broken: "wrong-id" | "no-getLocation" | "silent" | undefined, states: {...} })
(function (root) {
  function installStub(win, opts) {
    opts = opts || {};
    const states = Object.assign({ "user.basic": "granted", "user.full_name": "not_asked", "user.contact": "blocked", "location.fine": "not_asked", "location.coarse": "granted" }, opts.states || {});
    const known = ["user.basic", "user.full_name", "user.contact", "user.address", "location.coarse", "location.fine", "pay.request", "notify.inapp", "notify.sms", "notify.email"];
    const CAP = /^[a-z][a-z0-9_.]{1,38}$/;
    let counter = 0;
    const pending = new Map();
    const answer = (id, ok, payload) => {
      const msg = ok ? { adera: 1, id, ok: true, result: payload } : { adera: 1, id, ok: false, error: payload };
      if (opts.broken === "wrong-id") msg.id = "not-" + id;
      if (opts.broken === "silent") return;
      setTimeout(() => win.adera._receive(JSON.stringify(msg)), 5);
    };
    const err = (code) => ({ code, message: "stub: " + code });
    win.AderaBridge = {
      postMessage(str) {
        let m;
        try { m = JSON.parse(str); } catch { return answer(null, false, err("invalid_message")); }
        if (!m || typeof m !== "object" || Array.isArray(m)) return answer(null, false, err("invalid_message"));
        if (typeof m.id !== "string" || !m.id || m.id.length > 64) return answer(m.id == null ? null : m.id, false, err("invalid_message"));
        if (m.adera !== 1) return answer(m.id, false, err("invalid_message"));
        if (m.params !== undefined && JSON.stringify(m.params).length > 1024) return answer(m.id, false, err("invalid_message"));
        const p = m.params || {};
        const cap = p.capability;
        switch (m.method) {
          case "getPermission":
            if (typeof cap !== "string" || !CAP.test(cap)) return answer(m.id, false, err("invalid_params"));
            return answer(m.id, true, { state: known.includes(cap) ? states[cap] || "not_asked" : "not_available" });
          case "requestPermission":
            if (typeof cap !== "string" || !CAP.test(cap)) return answer(m.id, false, err("invalid_params"));
            if (!known.includes(cap)) return answer(m.id, true, { state: "not_available" });
            if (states[cap] === "blocked" || states[cap] === "granted") return answer(m.id, true, { state: states[cap] });
            states[cap] = "granted"; // the stub "user" always taps Allow
            return answer(m.id, true, { state: "granted" });
          case "openPermissionSettings": {
            const all = {};
            for (const k of known) all[k] = states[k] || "not_asked";
            return answer(m.id, true, { permissions: all });
          }
          case "getLocation": {
            const coarse = p.precision === "coarse";
            if (p.precision !== undefined && !["fine", "coarse"].includes(p.precision)) return answer(m.id, false, err("invalid_params"));
            const st = states[coarse ? "location.coarse" : "location.fine"] || "not_asked";
            if (st !== "granted") return answer(m.id, true, { state: st });
            return answer(m.id, true, coarse ? { state: "granted", place: { countryCode: "ET", region: "Addis Ababa", city: "Addis Ababa" } }
              : { state: "granted", location: { latitude: 9.0054, longitude: 38.7636, accuracyMeters: 12, at: new Date().toISOString() } });
          }
          default:
            return answer(m.id, false, err("unknown_method"));
        }
      },
    };
    const call = (method, params) => new Promise((resolve, reject) => {
      const id = "m_" + ++counter;
      pending.set(id, { resolve, reject, method });
      win.AderaBridge.postMessage(JSON.stringify({ adera: 1, id, method, params }));
    });
    win.adera = {
      requestPermission: (capability) => call("requestPermission", { capability }).then((r) => r.state),
      getPermission: (capability) => call("getPermission", { capability }).then((r) => r.state),
      openPermissionSettings: () => call("openPermissionSettings", {}),
      getLocation: (options) => call("getLocation", options || {}),
      _receive(json) {
        const m = typeof json === "string" ? JSON.parse(json) : json;
        const p = pending.get(m.id);
        if (!p) return;
        pending.delete(m.id);
        if (m.ok) p.resolve(m.result);
        else p.reject(Object.assign(new Error(m.error.message), { code: m.error.code }));
      },
    };
    if (opts.broken === "no-getLocation") delete win.adera.getLocation;
    return { states };
  }
  if (typeof module === "object" && module.exports) module.exports = { installStub };
  else root.installAderaStub = installStub;
})(typeof self !== "undefined" ? self : this);
