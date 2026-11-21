"""Bridge Lab backend against the REAL dev stack (no super app needed). Needs: the stack up, seed.py run, the bridge-lab container up.

  1. sign in as Demo Abebe (+251900000001, password) and get a real launch token: POST /api/v1/me/apps/bridge-lab/launch
  2. the launch url is the registered entryUrl + &launch=<token>
  3. Bridge Lab's backend verifies it against the JWKS (iss, aud, exp, env, kid, RS256); a replay is flagged
  4. seven tampered copies (signature, claims, alg none, HS256, unknown kid, garbage, other app) are rejected
  5. signed profile call through Bridge Lab (ADERA-SIG-1), Adera's response signature verifies, X-Request-Id logged
  6. consent changed through the USER API (the menu switch) -> the signed user.consent_changed callback reaches Bridge Lab
  7. the profile follows the consent (fullName present / 403 consent_required DENIED)
  8. in-app notification: 202 -> SENT, and the signed notification.status callback arrives
  9. the receiver refuses forged callbacks and recognises a duplicate delivery
 10. DEV synthetic users and badly signed partner calls answer as documented
 11. the backend log stream (SSE) carries the launch, partner and callback entries
Run: python3 -I bridge-lab/tests/e2e_backend.py      (LAB=http://localhost:8095 by default)
What it changes: Demo Abebe's consents for the Bridge Lab app (user.full_name, notify.inapp) and two in-app messages from Bridge Lab
in his inbox. Nothing of the demo apps is touched."""
import json, os, sys, time, urllib.error, urllib.request, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tests", "e2e"))
import staff  # noqa: E402
import userauth  # noqa: E402

G = staff.G
LAB = os.environ.get("LAB", "http://localhost:8095")
SESSION = "e2e" + uuid.uuid4().hex[:12]
CONFIG = json.load(open(os.path.join(ROOT, "bridge-lab", ".local", "config.json")))
results = []


def ok(label, cond, extra=""):
    results.append((label, bool(cond)))
    print(("PASS " if cond else "FAIL ") + label + ("" if cond or not extra else "  -> " + str(extra)[:400]), flush=True)
    return cond


def lab(method, path, body=None, session=SESSION):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(LAB + path, data=data, method=method, headers={"Content-Type": "application/json", "X-Lab-Session": session})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw, code = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read(), e.code
    try:
        return code, json.loads(raw or b"{}")
    except ValueError:
        return code, {"_raw": raw[:200].decode(errors="replace")}


def user(method, path, token, body=None):
    return staff.call(method, G + path, body, {"Authorization": "Bearer " + token})


def wait_callback(match, since, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        _, r = lab("GET", f"/api/callbacks?since={since}")
        for c in r.get("callbacks", []):
            if c.get("selftest"):
                continue
            if all((c.get("event") == v) if k == "event" else ((c.get("body") or {}).get(k) == v) for k, v in match.items()):
                return c
        time.sleep(1)
    return None


def last_seq():
    _, r = lab("GET", "/api/callbacks?since=999999999")
    return r.get("last", 0)


def main():
    st, h = lab("GET", "/healthz")
    if not ok("Bridge Lab container answers and is seeded", st == 200 and h.get("appId") == CONFIG["appId"] and h.get("key"), h):
        return
    # 1. sign in (password) and launch
    st, s = userauth.login_verified("+251900000001", "kebede")
    token = s.get("session_token")
    if not ok("Demo Abebe signs in with his password", token, f"{st} {json.dumps(s)[:200]}"):
        return
    st, launch = user("POST", "/api/v1/me/apps/bridge-lab/launch", token)
    ok("launch -> 200 with a token, 300 s", st == 200 and launch.get("launchToken") and launch.get("expiresInSeconds") == 300, f"{st} {launch}")
    tok = launch["launchToken"]
    ok("2. url = registered entryUrl + &launch=<token>", launch.get("url") == CONFIG["entryUrl"] + "&launch=" + tok, launch.get("url", "")[:80])
    # 3. verify
    st, v = lab("POST", "/api/launch/verify", {"token": tok, "label": "e2e"})
    claims = v.get("claims") or {}
    ok("3. the backend verifies the real token (RS256, kid, iss, aud, exp, env)", v.get("valid") and claims.get("aud") == CONFIG["appId"] and claims.get("iss") == "adera" and claims.get("env") == "dev", v.get("reason"))
    ok("   every mandatory check reported ok", all(c["ok"] for c in v.get("checks", []) if c["mandatory"]), v.get("checks"))
    ok("   exp - iat = 300 and sub is a pseudonymous id", claims.get("exp", 0) - claims.get("iat", 0) == 300 and claims.get("sub", "").startswith("u_p_"), claims)
    alias = claims.get("sub")
    st, again = lab("POST", "/api/launch/verify", {"token": tok, "label": "e2e replay"})
    ok("   the same token again is flagged as a replay (still valid)", again.get("valid") and again.get("replay") is True, again.get("reason"))
    # 4. tampered
    st, t = lab("POST", "/api/launch/tampered", {"token": tok})
    probes = t.get("probes", [])
    ok("4. seven tampered/foreign copies are all rejected by the right check", len(probes) == 7 and all(p["ok"] for p in probes), [(p["name"], p["failingCheck"]) for p in probes])
    # 5. profile
    st, p = lab("POST", "/api/partner/profile", {})
    ok("5. signed profile call: 200, alias = sub", p.get("status") == 200 and (p.get("body") or {}).get("alias") == alias, p.get("body"))
    ok("   Adera's response signature verifies", (p.get("responseSignature") or {}).get("verified"), p.get("responseSignature"))
    ok("   X-Request-Id and a trace id are recorded", p.get("requestId") and len(p.get("traceId", "")) == 32, (p.get("requestId"), p.get("traceId")))
    ok("   the signature itself is not shown (only its start)", "..." in (p.get("requestHeaders") or {}).get("X-Adera-Signature", ""))
    # 6. consent via the user API -> callback
    mark = last_seq()
    st, r = user("PUT", "/api/v1/me/apps/bridge-lab/permissions/user.full_name", token, {"state": "GRANTED"})
    ok("6. user API: switch user.full_name on (the menu switch)", st == 200 and r.get("state") == "GRANTED", f"{st} {r}")
    c = wait_callback({"event": "user.consent_changed", "capability": "user.full_name", "state": "GRANTED"}, mark)
    ok("   signed user.consent_changed GRANTED reached Bridge Lab and verified", c and c.get("verified") and (c.get("body") or {}).get("alias") == alias, c)
    st, p = lab("POST", "/api/partner/profile", {"fields": ["user.full_name"]})
    ok("7. profile ?fields=user.full_name -> 200 with fullName", p.get("status") == 200 and (p.get("body") or {}).get("fullName"), p.get("body"))
    mark = last_seq()
    st, r = user("PUT", "/api/v1/me/apps/bridge-lab/permissions/user.full_name", token, {"state": "DENIED"})
    c = wait_callback({"event": "user.consent_changed", "capability": "user.full_name", "state": "DENIED"}, mark)
    ok("   switched off -> callback DENIED", c and c.get("verified"), c)
    st, p = lab("POST", "/api/partner/profile", {"fields": ["user.full_name"]})
    b = p.get("body") or {}
    ok("   profile ?fields=user.full_name -> 403 consent_required DENIED", p.get("status") == 403 and b.get("error") == "consent_required" and b.get("state") == "DENIED", p.get("body"))
    # 8. notification
    st, r = user("PUT", "/api/v1/me/apps/bridge-lab/permissions/notify.inapp", token, {"state": "GRANTED"})
    ok("8. user API: switch notify.inapp on", st == 200, f"{st} {r}")
    mark = last_seq()
    st, n = lab("POST", "/api/partner/notify", {"kind": "inapp", "params": {"text": "Bridge Lab e2e check"}})
    nb = n.get("body") or {}
    ok("   in-app message -> 202 QUEUED IN_APP", n.get("status") == 202 and nb.get("status") == "QUEUED" and nb.get("channel") == "IN_APP", n.get("body"))
    ext = nb.get("externalId")
    final = None
    for _ in range(20):
        st, s2 = lab("GET", f"/api/partner/notifications/{ext}")
        final = (s2.get("body") or {}).get("status")
        if final in ("SENT", "FAILED", "UNREACHABLE", "SUPPRESSED", "EXPIRED"):
            break
        time.sleep(1)
    ok("   it ends SENT", final == "SENT", final)
    c = wait_callback({"event": "notification.status", "externalId": ext}, mark, 40)
    ok("   signed notification.status callback arrived and verified", c and c.get("verified") and (c.get("body") or {}).get("status") == "SENT", c)
    # 9. receiver self-tests
    st, r = lab("POST", "/api/callbacks/selftest", {"kind": "unsigned"})
    ok("9. an unsigned callback is refused (401)", r.get("status") == 401, r)
    st, r = lab("POST", "/api/callbacks/selftest", {"kind": "forged"})
    ok("   a callback signed with another key under Adera's key id is refused (401)", r.get("status") == 401, r)
    st, r = lab("POST", "/api/callbacks/selftest", {"kind": "duplicate"})
    ok("   the last real callback delivered again is recognised as a duplicate", r.get("duplicate") is True and r.get("status") == 204, r)
    # 10. synthetic users and bad signatures
    want = {"ok": (200, None), "noconsent": (403, "NOT_ASKED"), "denied": (403, "DENIED"), "blocked": (403, "BLOCKED"), "unreachable": (422, None)}
    good = True
    for k, (code, state) in want.items():
        st, r = lab("POST", "/api/partner/profile", {"alias": "u_p_test_" + k, "fields": ["user.full_name"]})
        good &= r.get("status") == code and (state is None or (r.get("body") or {}).get("state") == state)
    ok("10. DEV synthetic users answer 200 / 403 NOT_ASKED / DENIED / BLOCKED / 422", good)
    codes = {}
    for mode in ("bad-signature", "stale-timestamp", "unknown-key", "missing-signature"):
        st, r = lab("POST", "/api/partner/profile", {"alias": "u_p_test_ok", "mode": mode})
        codes[mode] = (r.get("status"), (r.get("body") or {}).get("error"))
    ok("    badly signed calls -> 401 invalid_signature / stale_timestamp / unknown_key / missing_signature",
       codes == {"bad-signature": (401, "invalid_signature"), "stale-timestamp": (401, "stale_timestamp"), "unknown-key": (401, "unknown_key"), "missing-signature": (401, "missing_signature")}, codes)
    st, r = lab("POST", "/api/partner/profile", {}, session="e2e-nolaunch-" + uuid.uuid4().hex[:6])
    ok("    a session without a verified launch token cannot call the partner API for a user (409 no_launch)", st == 409 and r.get("error") == "no_launch", r)
    # 11. the log stream
    req = urllib.request.Request(LAB + "/api/log/stream?s=" + SESSION, headers={"Accept": "text/event-stream"})
    kinds = set()
    with urllib.request.urlopen(req, timeout=10) as resp:
        buf = b""
        t0 = time.time()
        while time.time() - t0 < 4:
            line = resp.readline()
            if not line:
                break
            if line.startswith(b"data: "):
                try:
                    kinds.add(json.loads(line[6:]).get("kind"))
                except ValueError:
                    pass
            if {"launch", "partner", "callback"} <= kinds:
                break
    ok("11. the SSE log stream carries launch, partner and callback entries for this user", {"launch", "partner", "callback"} <= kinds, kinds)
    # report upload round trip
    st, r = lab("POST", "/api/report", {"markdown": "# test", "counts": {}})
    st2, md = 0, None
    try:
        with urllib.request.urlopen(LAB + r.get("md", "/x"), timeout=10) as resp:
            st2, md = resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        st2 = e.code
    ok("    report upload and Markdown download", st == 200 and st2 == 200 and md == "# test", (st, st2))
    passed = sum(1 for _, c in results if c)
    print(f"\n{passed}/{len(results)} checks passed")
    return passed == len(results)


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
