// Adera's public keys (GET /.well-known/jwks.json through the gateway), cached for an hour, refetched once for an unknown kid.
"use strict";
const crypto = require("node:crypto");

function createJwks(base, fetchImpl = fetch) {
  let cache = { at: 0, keys: new Map(), raw: null };

  async function load() {
    const res = await fetchImpl(base + "/.well-known/jwks.json");
    if (res.status !== 200) throw new Error("JWKS answered HTTP " + res.status);
    const body = await res.json();
    const keys = new Map();
    for (const k of body.keys || []) keys.set(k.kid, crypto.createPublicKey({ key: k, format: "jwk" }));
    cache = { at: Date.now(), keys, raw: body };
    return cache;
  }

  async function key(kid) {
    if (!cache.keys.has(kid) || Date.now() - cache.at > 3600_000) await load();
    const k = cache.keys.get(kid);
    if (!k) throw new Error("unknown key id " + JSON.stringify(kid) + " (not in the Adera JWKS)");
    return k;
  }

  return { key, load, kids: () => [...cache.keys.keys()] };
}

module.exports = { createJwks };
