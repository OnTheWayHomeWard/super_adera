// Per-session in-memory log with server-sent-event fan-out.
// A bucket is keyed by the user alias of the verified launch token (or "anon-..." before one was verified).
// Each bucket keeps the last MAX entries so reopening the page shows the history.
"use strict";

const MAX = 500;
const SHARED = "unmatched"; // callbacks whose alias Bridge Lab cannot tell (shown to every session)

class Logbook {
  constructor(max = MAX) {
    this.max = max;
    this.buckets = new Map(); // key -> { entries: [], clients: Set<res> }
    this.seq = 0;
  }

  bucket(key) {
    let b = this.buckets.get(key);
    if (!b) {
      b = { entries: [], clients: new Set() };
      this.buckets.set(key, b);
    }
    return b;
  }

  add(key, kind, title, data = {}) {
    const entry = { seq: ++this.seq, at: new Date().toISOString(), key, kind, title, ...data };
    const b = this.bucket(key);
    b.entries.push(entry);
    if (b.entries.length > this.max) b.entries.splice(0, b.entries.length - this.max);
    const line = "event: entry\ndata: " + JSON.stringify(entry) + "\n\n";
    const targets = key === SHARED ? [...this.buckets.values()].flatMap((x) => [...x.clients]) : [...b.clients];
    for (const res of new Set(targets)) {
      try { res.write(line); } catch { /* client gone */ }
    }
    // also print a one-line summary to the container log (docker compose logs bridge-lab; Loki collects it)
    console.log(`[${key}] ${kind} ${title}${data.requestId ? " request-id=" + data.requestId : ""}${data.traceId ? " trace-id=" + data.traceId : ""}`);
    return entry;
  }

  history(key, sinceSeq = 0) {
    const own = this.bucket(key).entries;
    const shared = key === SHARED ? [] : this.bucket(SHARED).entries;
    return [...own, ...shared].filter((e) => e.seq > sinceSeq).sort((a, b) => a.seq - b.seq);
  }

  // anonymous entries made before the launch token was verified move into the alias bucket
  merge(fromKey, toKey) {
    if (fromKey === toKey || !this.buckets.has(fromKey)) return;
    const from = this.buckets.get(fromKey);
    const to = this.bucket(toKey);
    to.entries = [...to.entries, ...from.entries].sort((a, b) => a.seq - b.seq).slice(-this.max);
    for (const res of from.clients) {
      try { res.write("event: rebind\ndata: " + JSON.stringify({ key: toKey }) + "\n\n"); } catch { /* gone */ }
    }
    this.buckets.delete(fromKey);
  }

  subscribe(key, res) {
    const b = this.bucket(key);
    b.clients.add(res);
    return () => b.clients.delete(res);
  }
}

module.exports = { Logbook, SHARED };
