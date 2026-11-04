// Verify an Adera launch token (a mini app backend does this when the super app opens the mini app with ?launch=<token>).
// Node 18+, no dependencies. The token is checked against Adera's PUBLIC key from /.well-known/jwks.json.
// Usage: ADERA_BASE=https://api.example ADERA_APP_ID=<your app id> node verify-launch-token.js <token>
// Checks: RS256 with the key named by the header's kid; iss == "adera"; aud == your app id; exp not passed (5 minute life); env matches.
// On success prints the claims: sub is the user's pseudonymous id (use it in /v1/users/{sub}/profile), scopes are the permissions the user
// allowed when the app was opened (a HINT only: Adera re-checks consent on every data call).
const crypto = require("node:crypto");

const BASE = process.env.ADERA_BASE.replace(/\/$/, "");
const APP_ID = process.env.ADERA_APP_ID;
const EXPECTED_ENV = process.env.ADERA_ENV || "dev"; // "dev" against the dev deployment, "prod" against production
const b64url = (s) => Buffer.from(s, "base64url");

async function aderaKey(kid) { // cache this for an hour in a real service; refetch once when a token carries an unknown kid
  const { keys } = await (await fetch(BASE + "/.well-known/jwks.json")).json();
  const jwk = keys.find((k) => k.kid === kid);
  if (!jwk) throw new Error("unknown kid " + kid);
  return crypto.createPublicKey({ key: jwk, format: "jwk" });
}

async function verify(token) {
  const [h, c, s] = token.split(".");
  const header = JSON.parse(b64url(h));
  if (header.alg !== "RS256") throw new Error("unexpected alg " + header.alg); // never accept "none" or an HMAC alg a caller chooses
  const key = await aderaKey(header.kid);
  if (!crypto.verify("sha256", Buffer.from(h + "." + c, "ascii"), key, b64url(s))) throw new Error("bad signature"); // RSASSA-PKCS1-v1_5
  const claims = JSON.parse(b64url(c));
  if (claims.iss !== "adera") throw new Error("wrong issuer");
  if (claims.aud !== APP_ID) throw new Error("this token is for another app");
  if (claims.exp < Date.now() / 1000) throw new Error("expired");
  if (claims.env !== EXPECTED_ENV) throw new Error("token of the " + claims.env + " environment");
  return claims;
}

verify(process.argv[2]).then((claims) => console.log(JSON.stringify(claims, null, 2)), (e) => { console.error("INVALID:", e.message); process.exit(1); });
