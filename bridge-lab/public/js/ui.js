// Screens: the test list, one test, the report, and the log drawer (Bridge / Backend). Plain DOM, no framework.
(function () {
  "use strict";
  const $ = (sel, el) => (el || document).querySelector(sel);
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const fmt = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>").replace(/`([^`]+)`/g, "<code>$1</code>");
  const json = (v) => (typeof v === "string" ? v : JSON.stringify(v, null, 2));
  const time = (iso) => (iso ? String(iso).slice(11, 23) : "");
  const S = window.Lab.STATUS;
  const VD = window.Lab.VERDICT;

  // ---------------------------------------------------------------------------------------------- boot
  const href = location.href;
  const u = new URL(href);
  const query = {};
  u.searchParams.forEach((v, k) => (query[k] = v));
  const openedAt = { href, origin: u.origin, pathname: u.pathname, search: u.search, hash: u.hash, query, launchToken: query.launch || null, referrer: document.referrer, ms: Date.now() };

  const monitor = window.BridgeMonitor.createMonitor(window);
  monitor.wrap();
  let ticks = 0;
  const wrapTimer = setInterval(() => { monitor.wrap(); if (++ticks > 40) { clearInterval(wrapTimer); setInterval(() => monitor.wrap(), 3000); } }, 500);

  let storage = null;
  try { storage = window.localStorage; storage.setItem("bridgeLab.probe", "1"); storage.removeItem("bridgeLab.probe"); } catch { storage = null; }

  let lab;
  const tests = window.LabTests.tests;
  const running = {}; // testId:index -> { until, hint }

  async function boot() {
    let config = {};
    try { config = await (await fetch("/api/config")).json(); } catch { config = {}; }
    lab = window.Lab.createLab({ win: window, storage, fetch: window.fetch.bind(window), monitor, tests, config, BridgeMonitor: window.BridgeMonitor });
    lab.openedAt = openedAt;
    window.lab = lab; // handy in a remote debugger
    await lab.api("POST", "/api/session");
    if (openedAt.launchToken) {
      const r = await lab.api("POST", "/api/launch/verify", { token: openedAt.launchToken, label: "page load" });
      lab.launch = r.data;
      if (r.data && r.data.claims && r.data.valid) {
        const list = (lab.store.read("launches") || []).filter((x) => x.jti !== r.data.claims.jti);
        list.push({ jti: r.data.claims.jti, iat: r.data.claims.iat, at: new Date().toISOString(), path: openedAt.pathname });
        lab.store.write("launches", list.slice(-10));
      }
    } else {
      const r = await lab.api("POST", "/api/session");
      if (r.data && r.data.claims) lab.launch = { valid: true, claims: r.data.claims, reused: true };
    }
    document.addEventListener("visibilitychange", () => {
      const list = lab.store.read("visibility") || [];
      list.push({ state: document.visibilityState, ms: Date.now() });
      lab.store.write("visibility", list.slice(-30));
      monitor.entries.push({ seq: 0, dir: "note", at: new Date().toISOString(), text: "page " + document.visibilityState });
      renderLog();
    });
    monitor.onEntry(() => scheduleLog());
    connectBackendLog();
    // resume after a navigating step (reload, another page, the probe page)
    const resume = lab.store.read("resume");
    if (resume && Date.now() - resume.at < 15 * 60 * 1000) {
      lab.store.remove("resume");
      const t = tests.find((x) => x.id === resume.test);
      if (t) {
        if (location.hash !== "#/t/" + t.id) location.hash = "#/t/" + t.id;
        const r = lab.result(t.id);
        let last = -1;
        t.steps.forEach((s, i) => { if (s.navigates && r.steps && r.steps[i]) last = i; });
        const next = t.steps[last + 1];
        if (next && next.resume) setTimeout(() => runStep(t, last + 1), 600);
      }
    }
    renderHeader();
    route();
  }

  // ---------------------------------------------------------------------------------------------- header
  function counts() {
    const res = lab.results();
    const c = { total: tests.length, pass: 0, fail: 0, notdev: 0, skip: 0, notrun: 0 };
    for (const t of tests) c[(res[t.id] && res[t.id].status) || "notrun"]++;
    return c;
  }
  function renderHeader() {
    const c = counts();
    const done = c.total - c.notrun;
    $("#progress").innerHTML = `<div class="bar"><span style="width:${(100 * done) / c.total}%"></span></div><div class="counts">${done}/${c.total} done &middot; ${S.pass.icon} ${c.pass} &middot; ${S.fail.icon} ${c.fail} &middot; ${S.notdev.icon} ${c.notdev} &middot; ${S.skip.icon} ${c.skip}</div>`;
    const l = lab.launch;
    const bridge = monitor.state.injected ? '<span class="pill ok">bridge injected</span>' : '<span class="pill bad">no window.adera</span>';
    let who;
    if (!openedAt.launchToken && !(l && l.reused)) who = '<span class="pill bad">no launch token</span>';
    else if (l && l.valid) who = `<span class="pill ok">user ${esc(l.claims.sub.slice(0, 12))}&hellip;</span>${l.reused ? '<span class="pill">session kept</span>' : ""}`;
    else who = `<span class="pill bad" title="${esc(l && l.reason)}">token rejected</span>`;
    $("#who").innerHTML = `${who}${bridge}<span class="pill">${esc(lab.config.env || "?")}</span>`;
    const n = $("#notice");
    n.innerHTML = openedAt.pathname.indexOf("/deeplink/") === 0 ? `Opened at the route <code>${esc(openedAt.pathname)}</code>: see <a href="#/t/D02">D02</a>&ndash;<a href="#/t/D05">D05</a>.` : !lab.config.appId ? "Bridge Lab is not registered yet: run <code>python3 -I bridge-lab/seed.py</code>." : "";
    n.hidden = !n.innerHTML;
  }

  // ---------------------------------------------------------------------------------------------- routing
  function route() {
    const h = location.hash || "#/";
    if (h.indexOf("#/t/") === 0) renderTest(h.slice(4));
    else if (h === "#/report") renderReport();
    else renderHome();
    window.scrollTo(0, 0);
  }
  window.addEventListener("hashchange", route);

  // ---------------------------------------------------------------------------------------------- home
  let filter = "all";
  function renderHome() {
    const res = lab.results();
    const groups = window.LabTests.groups;
    const chips = ["all", "notrun", "pass", "fail", "notdev", "skip"].map((k) => `<button class="chip ${filter === k ? "on" : ""}" data-f="${k}">${k === "all" ? "All" : S[k].icon + " " + S[k].label}</button>`).join("");
    let html = `<div class="chips">${chips}</div>`;
    for (const g of groups) {
      const list = tests.filter((t) => t.group === g && (filter === "all" || ((res[t.id] && res[t.id].status) || "notrun") === filter));
      if (!list.length) continue;
      const all = tests.filter((t) => t.group === g);
      const done = all.filter((t) => res[t.id] && res[t.id].status && res[t.id].status !== "notrun").length;
      html += `<section class="group"><h2>${esc(g)} <small>${done}/${all.length}</small></h2><ul class="tests">`;
      for (const t of list) {
        const st = (res[t.id] && res[t.id].status) || "notrun";
        const kinds = [...new Set(t.steps.map((s) => (s.kind === "auto" ? "auto" : "human")))].join(" + ");
        html += `<li><a href="#/t/${t.id}"><span class="st">${S[st].icon}</span><span class="tid">${t.id}</span><span class="tt">${fmt(t.title)}</span><span class="kind">${kinds}</span></a></li>`;
      }
      html += "</ul></section>";
    }
    $("#main").innerHTML = html;
    $("#main").querySelectorAll(".chip").forEach((b) => (b.onclick = () => { filter = b.dataset.f; renderHome(); }));
  }

  // ---------------------------------------------------------------------------------------------- one test
  function verdictBlock(v, test) {
    if (!v) return "";
    const vd = VD[v.verdict] || VD.info;
    let h = `<div class="verdict v-${v.verdict}"><div class="vh">${vd.icon} <b>${vd.label}</b> <small>${time(v.at)}</small></div>`;
    if (v.expected !== undefined) h += `<div class="kv"><span>expected</span><code>${esc(json(v.expected))}</code></div>`;
    if (v.actual !== undefined) h += `<div class="kv"><span>actual</span><pre>${esc(json(v.actual))}</pre></div>`;
    if (v.note) h += `<div class="note">${fmt(v.note)}</div>`;
    if (v.verdict === "notdev") {
      h += `<div class="must"><b>What the super app must do:</b> ${fmt(v.mustDo || test.mustDo || "")}`;
      if (v.spec || test.spec) h += ` <a href="${esc(lab.specUrl(v.spec || test.spec))}" target="_blank" rel="noopener">spec</a>`;
      h += "</div>";
    }
    return h + "</div>";
  }

  function renderTest(id) {
    const test = tests.find((t) => t.id === id);
    if (!test) { $("#main").innerHTML = "<p>No such test.</p>"; return; }
    const ctx = lab.context(test.id);
    if (!ctx.openedMs) { ctx.openedMs = Date.now(); lab.saveContext(test.id); }
    if (!ctx.mark) lab.callbackSeq().then((n) => { if (!ctx.mark) { ctx.mark = n; lab.saveContext(test.id); } });
    const r = lab.result(test.id);
    const idx = tests.indexOf(test);
    let h = `<div class="crumbs"><a href="#/">&larr; All tests</a><span>${esc(test.group)}</span></div>`;
    h += `<h1><span class="tid">${test.id}</span> ${fmt(test.title)}</h1><p class="goal">${fmt(test.goal || "")}</p>`;
    if (test.pre && test.pre.length) h += `<div class="pre"><b>Before you start</b><ul>${test.pre.map((p) => `<li>${fmt(p)}</li>`).join("")}</ul></div>`;
    if (test.spec) h += `<p class="spec">Spec: <a href="${esc(lab.specUrl(test.spec))}" target="_blank" rel="noopener">${esc(test.spec)}</a></p>`;
    h += '<ol class="steps">';
    test.steps.forEach((s, i) => {
      const v = r.steps && r.steps[i];
      const key = test.id + ":" + i;
      h += `<li class="step k-${s.kind}" id="step-${i}">`;
      if (s.kind === "human") h += `<div class="human">&#128100; ${fmt(s.text)}</div>`;
      else if (s.kind === "question") {
        h += `<div class="q">&#10067; ${fmt(s.text)}</div><div class="btns"><button class="yes" data-q="${i}" data-a="yes">Yes</button><button class="no" data-q="${i}" data-a="no">No</button></div>`;
      } else {
        h += `<div class="auto"><div class="lbl">&#9881;&#65039; ${fmt(s.label)}</div><div class="exp">expected: ${fmt(s.expect || "")}</div>`;
        const run = running[key];
        if (run) {
          const hint = typeof s.hint === "function" ? s.hint(lab, ctx) : s.hint;
          h += `<div class="running">${hint ? `<div class="hint">${fmt(hint)}</div>` : ""}<div class="count" data-until="${run.until}">&#9203; waiting&hellip;</div></div>`;
        } else h += `<button class="run" data-run="${i}">${v ? "Run again" : "Run step"}</button>${s.hint && !v ? `<div class="hint soft">${fmt(typeof s.hint === "function" ? "When it runs: " + s.hint(lab, ctx) : "When it runs: " + s.hint)}</div>` : ""}`;
        h += "</div>";
      }
      h += verdictBlock(v, test) + "</li>";
    });
    h += "</ol><div id=\"frame-slot\"></div>";
    const sug = lab.suggest(test);
    h += `<div class="final"><div><b>Your verdict</b> ${sug ? `<span class="sug">Bridge Lab suggests: ${S[sug].icon} ${S[sug].label}</span>` : ""}</div>
      <div class="btns">${["pass", "fail", "notdev", "skip"].map((k) => `<button class="mk ${r.status === k ? "on" : ""}" data-mk="${k}">${S[k].icon} ${S[k].label}</button>`).join("")}</div>
      <textarea id="note" rows="2" placeholder="Note (what you saw)">${esc(r.note || "")}</textarea>
      <div class="btns"><button id="save-note">Save note</button><button id="reset-test" class="ghost">Reset this test</button></div></div>`;
    h += `<div class="pager">${idx > 0 ? `<a href="#/t/${tests[idx - 1].id}">&larr; ${tests[idx - 1].id}</a>` : "<span></span>"}${idx < tests.length - 1 ? `<a href="#/t/${tests[idx + 1].id}">${tests[idx + 1].id} &rarr;</a>` : ""}</div>`;
    $("#main").innerHTML = h;
    $("#main").querySelectorAll("[data-run]").forEach((b) => (b.onclick = () => runStep(test, Number(b.dataset.run))));
    $("#main").querySelectorAll("[data-q]").forEach((b) => (b.onclick = async () => { await lab.runStep(test, Number(b.dataset.q), b.dataset.a); lab.note(test.id + " step " + (Number(b.dataset.q) + 1) + ": " + b.dataset.a); rerender(test); }));
    $("#main").querySelectorAll("[data-mk]").forEach((b) => (b.onclick = () => {
      lab.saveResult(test.id, { status: b.dataset.mk, note: $("#note").value });
      lab.note(test.id + " marked " + S[b.dataset.mk].label + ($("#note").value ? ": " + $("#note").value : ""));
      renderHeader();
      rerender(test);
    }));
    $("#save-note").onclick = () => { lab.saveResult(test.id, { note: $("#note").value }); rerender(test); };
    $("#reset-test").onclick = () => { if (confirm("Reset " + test.id + "?")) { lab.reset(test.id); renderHeader(); rerender(test); } };
  }

  function rerender(test) {
    if (location.hash === "#/t/" + test.id) {
      const y = window.scrollY;
      renderTest(test.id);
      window.scrollTo(0, y);
    }
  }

  async function runStep(test, i) {
    const s = test.steps[i];
    const key = test.id + ":" + i;
    if (running[key]) return;
    running[key] = { until: Date.now() + (s.timeout || 10000) };
    rerender(test);
    const v = await lab.runStep(test, i);
    delete running[key];
    renderHeader();
    rerender(test);
    const el = document.getElementById("step-" + i);
    if (el && el.scrollIntoView) el.scrollIntoView({ block: "nearest" });
    return v;
  }

  setInterval(() => {
    document.querySelectorAll(".count[data-until]").forEach((el) => {
      const left = Math.max(0, Math.round((Number(el.dataset.until) - Date.now()) / 1000));
      el.textContent = "⏳ waiting for an answer: " + Math.floor(left / 60) + ":" + String(left % 60).padStart(2, "0") + " left";
    });
  }, 1000);

  // ---------------------------------------------------------------------------------------------- report
  function download(name, text, type) {
    try {
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([text], { type }));
      a.download = name;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
    } catch (e) { alert("Download failed here: use Copy or Upload. " + e.message); }
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); toast("Copied"); } catch {
      const ta = document.createElement("textarea");
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy"); toast("Copied"); } catch { toast("Copy failed"); }
      ta.remove();
    }
  }
  function toast(t) {
    const el = $("#toast");
    el.textContent = t;
    el.hidden = false;
    setTimeout(() => (el.hidden = true), 1800);
  }

  function renderReport() {
    const r = lab.report();
    let h = `<div class="crumbs"><a href="#/">&larr; All tests</a></div><h1>Report</h1>
      <p>${r.counts.total} tests: ${S.pass.icon} ${r.counts.pass} pass, ${S.fail.icon} ${r.counts.fail} fail, ${S.notdev.icon} ${r.counts.notdev} not developed, ${S.skip.icon} ${r.counts.skip} skipped, ${r.counts.notrun} not run.</p>
      <div class="btns wrap"><button id="dl-md">Download Markdown</button><button id="dl-json">Download JSON</button><button id="cp-md">Copy Markdown</button><button id="up">Upload to the Bridge Lab server</button><button id="reset-all" class="ghost">Reset all results</button></div>
      <p id="up-out" class="soft"></p><h2>What the super app still needs to build</h2>`;
    if (!r.superAppTodo.length) h += '<p class="soft">Nothing recorded yet (mark tests "Not developed yet", or run steps that detect it).</p>';
    else h += "<ul class=\"todo\">" + r.superAppTodo.map((t) => `<li><a href="#/t/${t.id}"><b>${t.id}</b> ${fmt(t.title)}</a>${t.note ? ` <i>(${esc(t.note)})</i>` : ""}<ul>${t.mustDo.map((m) => `<li>${fmt(m)}</li>`).join("")}</ul>${t.spec ? `<a class="soft" href="${esc(lab.specUrl(t.spec))}" target="_blank" rel="noopener">${esc(t.spec)}</a>` : ""}</li>`).join("") + "</ul>";
    for (const g of r.groups) {
      h += `<h2>${esc(g.name)}</h2><table class="rt"><tbody>` + g.tests.map((t) => `<tr><td>${S[t.status].icon}</td><td><a href="#/t/${t.id}">${t.id}</a></td><td>${fmt(t.title)}${t.note ? `<div class="soft">${esc(t.note)}</div>` : ""}</td></tr>`).join("") + "</tbody></table>";
    }
    $("#main").innerHTML = h;
    const md = () => lab.reportMarkdown();
    const full = () => Object.assign(lab.report(), { bridgeLog: monitor.entries, backendLog: backendEntries.slice(-500) });
    $("#dl-md").onclick = () => download("bridge-lab-report.md", md(), "text/markdown");
    $("#dl-json").onclick = () => download("bridge-lab-report.json", JSON.stringify(full(), null, 2), "application/json");
    $("#cp-md").onclick = () => copy(md());
    $("#up").onclick = async () => {
      const res = await fetch("/api/report", { method: "POST", headers: { "Content-Type": "application/json", "X-Lab-Session": lab.sessionId }, body: JSON.stringify(Object.assign(full(), { markdown: md() })) });
      const d = await res.json().catch(() => ({}));
      $("#up-out").innerHTML = d.ok ? `Uploaded. On the laptop: <code>${esc((lab.config.publicUrl || "") + d.md)}</code> and <code>${esc((lab.config.publicUrl || "") + d.json)}</code>` : "Upload failed: " + esc(JSON.stringify(d));
    };
    $("#reset-all").onclick = () => { if (confirm("Reset ALL results?")) { lab.reset(); renderHeader(); renderReport(); } };
  }

  // ---------------------------------------------------------------------------------------------- log drawer
  let tab = "bridge";
  let methodFilter = "";
  const backendEntries = [];
  let logPending = false;
  function scheduleLog() {
    if (logPending) return;
    logPending = true;
    setTimeout(() => { logPending = false; renderLog(); }, 150);
  }

  function grafana(kind, id) {
    const g = lab && lab.config.grafanaUrl;
    if (!g || !id) return "";
    const pane = kind === "trace"
      ? { datasource: "tempo", queries: [{ refId: "A", datasource: { type: "tempo", uid: "tempo" }, queryType: "traceql", query: id }], range: { from: "now-1h", to: "now" } }
      : { datasource: "loki", queries: [{ refId: "A", datasource: { type: "loki", uid: "loki" }, expr: '{service_name=~".+"} |= "' + id + '"' }], range: { from: "now-1h", to: "now" } };
    return g + "/explore?schemaVersion=1&orgId=1&panes=" + encodeURIComponent(JSON.stringify({ a: pane }));
  }

  function bridgeRow(e) {
    const arrow = { out: "&#8593; page&rarr;super", in: "&#8595; super&rarr;page", call: "&#9654; call", result: "&#9664; result", note: "&#8226; note" }[e.dir] || e.dir;
    const bad = e.violations && e.violations.length;
    const head = `<span class="t">${time(e.at)}</span><span class="dir d-${e.dir}">${arrow}</span>${e.method ? `<span class="m">${esc(e.method)}</span>` : ""}${e.id ? `<span class="id">#${esc(String(e.id).slice(-10))}</span>` : ""}${e.ms != null ? `<span class="ms">${e.ms} ms</span>` : ""}${e.unsolicited ? '<span class="pill bad">unsolicited</span>' : ""}${e.late ? '<span class="pill">late</span>' : ""}${bad ? '<span class="pill bad">spec!</span>' : ""}${e.text ? `<span>${esc(e.text)}</span>` : ""}`;
    const body = e.payload !== undefined ? `<pre>${esc(json(e.payload))}</pre>` : "";
    const v = bad ? `<div class="viol">${e.violations.map(esc).join("<br>")}</div>` : "";
    return `<details class="row ${bad ? "bad" : ""}"><summary>${head}</summary>${v}${body}${e.note ? `<div class="soft">${esc(e.note)}</div>` : ""}</details>`;
  }

  function backendRow(e) {
    const links = [e.traceId && grafana("trace", e.traceId) ? `<a href="${esc(grafana("trace", e.traceId))}" target="_blank" rel="noopener">trace</a>` : "", e.requestId && grafana("logs", e.requestId) ? `<a href="${esc(grafana("logs", e.requestId))}" target="_blank" rel="noopener">logs</a>` : ""].filter(Boolean).join(" ");
    const bad = (e.kind === "launch" && e.verdict === "rejected") || (e.kind === "callback" && !e.verified) || (e.kind === "partner" && (e.status >= 400 || e.error));
    const detail = Object.assign({}, e);
    ["seq", "at", "key", "kind", "title"].forEach((k) => delete detail[k]);
    return `<details class="row ${bad ? "warn" : ""}"><summary><span class="t">${time(e.at)}</span><span class="pill k-${esc(e.kind)}">${esc(e.kind)}</span><span>${esc(e.title)}</span>${e.ms != null ? `<span class="ms">${e.ms} ms</span>` : ""}${links ? `<span class="links">${links}</span>` : ""}</summary>${e.traceId ? `<div class="soft">trace id ${esc(e.traceId)} &middot; X-Request-Id ${esc(e.requestId || "-")}</div>` : ""}<pre>${esc(json(detail))}</pre></details>`;
  }

  function renderLog() {
    if (!lab) return;
    $("#bcount").textContent = monitor.entries.length;
    $("#kcount").textContent = backendEntries.length;
    document.querySelectorAll("#drawer [data-tab]").forEach((b) => b.classList.toggle("on", b.dataset.tab === tab));
    const methods = [...new Set(monitor.entries.map((e) => e.method).filter(Boolean))];
    const sel = $("#mfilter");
    sel.hidden = tab !== "bridge";
    sel.innerHTML = `<option value="">all methods</option>` + methods.map((m) => `<option ${m === methodFilter ? "selected" : ""}>${esc(m)}</option>`).join("");
    if ($("#drawer").classList.contains("collapsed")) return;
    const list = tab === "bridge" ? monitor.entries.filter((e) => !methodFilter || e.method === methodFilter).slice(-300).reverse().map(bridgeRow) : backendEntries.slice(-300).reverse().map(backendRow);
    $("#log-list").innerHTML = list.join("") || '<p class="soft">Nothing yet.</p>';
  }

  function connectBackendLog() {
    let since = 0;
    const open = () => {
      const es = new EventSource("/api/log/stream?s=" + encodeURIComponent(lab.sessionId) + "&since=" + since);
      es.addEventListener("entry", (ev) => {
        const e = JSON.parse(ev.data);
        if (e.seq <= since) return;
        since = e.seq;
        backendEntries.push(e);
        if (backendEntries.length > 1000) backendEntries.splice(0, 200);
        scheduleLog();
      });
      es.addEventListener("rebind", () => { es.close(); setTimeout(open, 200); });
      es.onerror = () => { /* EventSource reconnects by itself */ };
    };
    open();
  }

  function setupDrawer() {
    $("#drawer-toggle").onclick = () => {
      $("#drawer").classList.toggle("collapsed");
      $("#drawer-toggle").innerHTML = $("#drawer").classList.contains("collapsed") ? "&#9650; Logs" : "&#9660; Hide";
      renderLog();
    };
    document.querySelectorAll("#drawer [data-tab]").forEach((b) => (b.onclick = () => {
      tab = b.dataset.tab;
      if ($("#drawer").classList.contains("collapsed")) $("#drawer-toggle").click();
      else renderLog();
    }));
    $("#mfilter").onchange = (e) => { methodFilter = e.target.value; renderLog(); };
    $("#log-copy").onclick = () => copy(JSON.stringify(tab === "bridge" ? monitor.entries : backendEntries, null, 2));
    $("#log-dl").onclick = () => download(tab === "bridge" ? "bridge-log.json" : "backend-log.json", JSON.stringify(tab === "bridge" ? monitor.entries : backendEntries, null, 2), "application/json");
    $("#log-clear").onclick = () => { if (tab === "bridge") monitor.clear(); else backendEntries.splice(0); renderLog(); };
  }

  setupDrawer();
  boot().catch((e) => { $("#main").innerHTML = "<p class='bad'>Bridge Lab could not start: " + esc(e.message) + "</p>"; });
})();
