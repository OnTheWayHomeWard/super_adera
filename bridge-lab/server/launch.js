// Launch-token verification as documented in docs/guides/app-permissions/mini-app.html#launch:
// RS256 only, key named by kid from the Adera JWKS, iss "adera", aud = our app id, exp not passed, env = our deployment.
// Every check is reported (the page shows them) and the first failing mandatory one is the reason.
"use strict";
const crypto = require("node:crypto");

const b64url = (s) => Buffer.from(s, "base64url");

function decodePart(part) {
  try {
    const v = JSON.parse(b64url(part).toString("utf8"));
    return v && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

/**
 * @param token string
 * @param opts { jwks, appId, env, now?: seconds, maxLifeSeconds?: 300, seenJti?: Map }
 * @returns { valid, reason, header, claims, checks: [{check, ok, detail, mandatory}], replay }
 */
async function verifyLaunchToken(token, opts) {
  const now = opts.now ?? Math.floor(Date.now() / 1000);
  const checks = [];
  let reason = null;
  const check = (name, ok, detail, mandatory = true) => {
    checks.push({ check: name, ok: !!ok, detail, mandatory });
    if (!ok && mandatory && !reason) reason = name + ": " + detail;
    return ok;
  };
  const done = (header = null, claims = null, replay = false) => ({ valid: !reason, reason, header, claims, checks, replay });

  if (typeof token !== "string" || !check("format", /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$/.test(token), "three base64url parts separated by dots")) {
    return done();
  }
  const [h, c, s] = token.split(".");
  const header = decodePart(h);
  const claims = decodePart(c);
  if (!check("format", header && claims, header && claims ? "header and claims are JSON objects" : "header or claims are not JSON objects")) return done(header, claims);
  if (!check("alg", header.alg === "RS256", "alg is " + JSON.stringify(header.alg) + " (only RS256 is accepted, never none or an HMAC alg)")) return done(header, claims);
  let key;
  try {
    key = await opts.jwks.key(header.kid);
    check("kid", true, "key " + header.kid + " found in the Adera JWKS");
  } catch (e) {
    check("kid", false, e.message);
    return done(header, claims);
  }
  const sigOk = s.length > 0 && crypto.verify("sha256", Buffer.from(h + "." + c, "ascii"), key, b64url(s));
  if (!check("signature", sigOk, sigOk ? "RS256 signature verifies with " + header.kid : "bad signature")) return done(header, claims);
  check("iss", claims.iss === "adera", "iss is " + JSON.stringify(claims.iss) + " (want \"adera\")");
  check("aud", claims.aud === opts.appId, claims.aud === opts.appId ? "aud is this app's id" : "this token is for another app (aud " + JSON.stringify(claims.aud) + ", this app " + opts.appId + ")");
  check("exp", typeof claims.exp === "number" && claims.exp > now, typeof claims.exp !== "number" ? "no exp" : claims.exp > now ? `expires in ${claims.exp - now} s` : `expired ${now - claims.exp} s ago`);
  check("env", claims.env === opts.env, "env is " + JSON.stringify(claims.env) + " (this deployment: " + opts.env + ")");
  check("sub", typeof claims.sub === "string" && /^u_p_[A-Za-z0-9_]{1,60}$/.test(claims.sub), "sub " + JSON.stringify(claims.sub) + " (a pseudonymous id u_p_...)");
  check("scopes", Array.isArray(claims.scopes), Array.isArray(claims.scopes) ? "scopes: " + claims.scopes.join(", ") : "scopes is not a list", false);
  const life = claims.exp - claims.iat;
  check("lifetime", typeof claims.iat === "number" && life > 0 && life <= (opts.maxLifeSeconds ?? 300), `exp - iat = ${life} s (documented: 300)`, false);
  check("iat", typeof claims.iat === "number" && claims.iat <= now + 60, typeof claims.iat === "number" ? `issued ${now - claims.iat} s ago` : "no iat", false);
  let replay = false;
  if (typeof claims.jti === "string" && claims.jti) {
    const seen = opts.seenJti;
    if (seen) {
      replay = seen.has(claims.jti);
      if (!replay) seen.set(claims.jti, now);
    }
    check("jti", !replay, replay ? "this jti was verified before (a replay; the page may simply have been reloaded)" : "first use of jti " + claims.jti, false);
  } else {
    check("jti", false, "no jti", false);
  }
  return done(header, claims, replay);
}

// Deliberately broken copies of a real token, for the "tampered token" test (L07).
function tamperedVariants(token, otherAppId = "00000000-0000-0000-0000-000000000000") {
  const [h, c, s] = token.split(".");
  const header = decodePart(h) || {};
  const claims = decodePart(c) || {};
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const flip = (str) => {
    const last = str.slice(-6);
    const swapped = [...last].map((ch) => (ch === "A" ? "B" : "A")).join("");
    return str.slice(0, -6) + swapped;
  };
  return [
    { name: "signature changed", token: h + "." + c + "." + flip(s), expect: "signature" },
    { name: "claims changed (sub swapped), original signature", token: h + "." + enc({ ...claims, sub: "u_p_attacker" }) + "." + s, expect: "signature" },
    { name: "alg none, no signature", token: enc({ ...header, alg: "none" }) + "." + c + ".", expect: "alg" },
    { name: "alg HS256 (HMAC with the public key)", token: enc({ ...header, alg: "HS256" }) + "." + c + "." + s, expect: "alg" },
    { name: "unknown kid", token: enc({ ...header, kid: "not-an-adera-key" }) + "." + c + "." + s, expect: "kid" },
    { name: "garbage", token: "not-a-token", expect: "format" },
    { name: "token of another app (aud checked against " + otherAppId + ")", token, expectAud: otherAppId, expect: "aud" },
  ];
}

module.exports = { verifyLaunchToken, tamperedVariants, decodePart };
