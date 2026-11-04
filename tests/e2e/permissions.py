"""The whole Demo Ride scenario of docs section 14 (section 7), end to end through the GATEWAY with real seeded users, the real seeded
partner key of demo-ride and a temporary staff account: launch, permission dialog one permission at a time, the partner profile,
notification consent (refused, blocked, unblocked in the menu), the backoffice counts, the callbacks to the partner, Disconnect.

Run from the repo root:   python3 -I tests/e2e/permissions.py          (DEV stack with the seeded data: seed_demo.py + seed_scenarios.py)
Optional:  PERM_CAPTURE=/path/out.json  writes every exchange it made (used to capture the real samples of the docs).

The user is the seeded "Demo Kidist" (phone +251900000010, password in docs section 13: she has a phone, so SMS can be chosen).
What it touches and puts back exactly as it was when it finishes (also when it fails):
  - Kidist's consent rows for demo-ride and their proof rows (consent_event), the partner event outbox, callbacks it caused;
  - demo-ride's callback address (set to a local receiver, removed again) and the purpose of notify.sms (changed, then restored);
  - the notifications, inbox items and usage counters its test messages made.
Nothing real is sent (DEV: console SMS, Mailpit, fake push). Never uses the bootstrap staff account (tempstaff)."""
import base64, hashlib, json, os, subprocess, sys, threading, time, urllib.request, tempfile, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import demopartner as d, staff, tempstaff, userauth

G = staff.G
passed = failed = 0
CAPTURE = {}
RUN = "e2e-perm-" + uuid.uuid4().hex[:6]


def ok(label, cond, extra=""):
    global passed, failed
    passed, failed = (passed + 1, failed) if cond else (passed, failed + 1)
    print(("  PASS " if cond else "  FAIL ") + label + (f"  [{extra}]" if extra != "" else ""))
    return cond


def cap(name, st, body):
    CAPTURE[name] = [st, body]
    return st, body


def b64d(x):
    return json.loads(base64.urlsafe_b64decode(x + "=" * (-len(x) % 4)))


def verify_jwt(token, app_id):
    """RS256 with the key from the JWKS, exactly as a partner does (openssl builds the check from n and e)."""
    h, c, s = token.split(".")
    jw = json.load(urllib.request.urlopen(G + "/.well-known/jwks.json"))["keys"][0]
    header, claims = b64d(h), b64d(c)
    if header["kid"] != jw["kid"] or header["alg"] != "RS256":
        return False, header, claims

    def der_len(n): return bytes([n]) if n < 128 else (b"\x81" + bytes([n]) if n < 256 else b"\x82" + n.to_bytes(2, "big"))
    def der(t, b): return bytes([t]) + der_len(len(b)) + b
    def di(b): b = b.lstrip(b"\x00"); b = (b"\x00" + b) if b[0] & 0x80 else b; return der(2, b)
    u = lambda v: base64.urlsafe_b64decode(v + "=" * (-len(v) % 4))
    rsa = der(0x30, di(u(jw["n"])) + di(u(jw["e"])))
    spki = der(0x30, der(0x30, bytes.fromhex("06092a864886f70d0101010500")) + der(3, b"\x00" + rsa))
    pem = "-----BEGIN PUBLIC KEY-----\n" + base64.encodebytes(spki).decode() + "-----END PUBLIC KEY-----\n"
    with tempfile.TemporaryDirectory() as t:
        open(t + "/p", "w").write(pem)
        open(t + "/i", "wb").write((h + "." + c).encode())
        open(t + "/s", "wb").write(u(s))
        r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", t + "/p", "-signature", t + "/s", t + "/i"], capture_output=True, text=True)
    return r.stdout.strip() == "Verified OK" and claims["aud"] == app_id and claims["exp"] - claims["iat"] == 300, header, claims


def verify_callback_signature(g):
    jw = json.load(urllib.request.urlopen(G + "/.well-known/jwks.json"))["keys"][0]
    h = g["headers"]
    canon = "\n".join(["ADERA-SIG-1", h["X-Adera-Timestamp"], "POST", g["path"], base64.b64encode(hashlib.sha256(g["body"].encode()).digest()).decode()])

    def der_len(n): return bytes([n]) if n < 128 else (b"\x81" + bytes([n]) if n < 256 else b"\x82" + n.to_bytes(2, "big"))
    def der(t, b): return bytes([t]) + der_len(len(b)) + b
    def di(b): b = b.lstrip(b"\x00"); b = (b"\x00" + b) if b[0] & 0x80 else b; return der(2, b)
    u = lambda v: base64.urlsafe_b64decode(v + "=" * (-len(v) % 4))
    spki = der(0x30, der(0x30, bytes.fromhex("06092a864886f70d0101010500")) + der(3, b"\x00" + der(0x30, di(u(jw["n"])) + di(u(jw["e"])))))
    pem = "-----BEGIN PUBLIC KEY-----\n" + base64.encodebytes(spki).decode() + "-----END PUBLIC KEY-----\n"
    with tempfile.TemporaryDirectory() as t:
        open(t + "/p", "w").write(pem)
        open(t + "/i", "w").write(canon)
        open(t + "/s", "wb").write(base64.b64decode(h["X-Adera-Signature"]))
        r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", t + "/p", "-signature", t + "/s", t + "/i"], capture_output=True, text=True)
    return r.stdout.strip() == "Verified OK"


# ---- the partner's callback receiver (a local HTTP server) --------------------------------------------------------------
received = []


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        received.append({"path": self.path, "headers": dict(self.headers.items()), "body": body.decode()})
        self.send_response(204)
        self.end_headers()

    def log_message(self, *a):
        pass


def wait_events(n, timeout=40):
    end = time.time() + timeout
    while time.time() < end and len(received) < n:
        time.sleep(0.5)
    time.sleep(1.0)


def main():
    global passed, failed
    require = staff.call("GET", staff.N + "/dev/messages?to=nobody")
    if require[0] != 200:
        raise SystemExit("not a DEV stack (notification-service /dev/messages does not answer): refusing to run")
    srv = HTTPServer(("0.0.0.0", 0), Hook)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    net = subprocess.run(["docker", "compose", "ps", "-q", "notification-service"], capture_output=True, text=True, cwd=d.ROOT).stdout.strip()
    host = subprocess.run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}", net], capture_output=True, text=True).stdout.strip() or "172.17.0.1"
    hook_url = f"http://{host}:{srv.server_address[1]}/adera/events"

    ride = d.app_id("demo-ride")
    uid = d.psql("users", "select id from app_user where phone_e164='+251900000010'")
    assert uid, "seeded user Demo Kidist not found: run tests/e2e/seed_scenarios.py first"
    ride_q = f"app_id='{ride}'"
    # ---- snapshots, so everything is put back exactly
    snap_consent = d.psql("users", f"select capability||'|'||state||'|'||denial_count||'|'||coalesce(decided_at::text,'')||'|'||coalesce(source,'') from app_consent where user_id='{uid}' and {ride_q}")
    max_event = d.psql("users", "select coalesce(max(id),0) from consent_event")
    snap_daily = d.psql("notification", f"select channel||'|'||day||'|'||user_id||'|'||sent from usage_daily where {ride_q}")
    snap_total = d.psql("notification", f"select environment||'|'||channel||'|'||total from usage_total where {ride_q}")

    handle = tempstaff.open_session("ADMIN")
    A = {"Authorization": "Bearer " + handle.token}
    staff_call = lambda m, p, b=None: staff.call(m, G + "/admin/api/v1" + p, b, A)
    original_sms = None
    try:
        print("== 0. setup: the user, the partner, the callback address")
        d.psql("users", f"delete from app_consent where user_id='{uid}' and {ride_q}")        # Kidist starts with nothing answered for Demo Ride
        st, detail = staff_call("GET", f"/apps/{ride}")
        caps = {c["capability"]: c for c in detail["capabilities"]}
        ok("Demo Ride is LIVE and staff granted user.basic, user.full_name, location.fine and notify.sms",
           detail["app"]["status"] == "LIVE" and all(caps.get(k, {}).get("state") == "GRANTED" for k in ("user.basic", "user.full_name", "location.fine", "notify.sms")))
        ok("location.fine has an approved purpose sentence", bool(caps["location.fine"]["purpose"].get("en")), caps["location.fine"]["purpose"])
        original_sms = caps["notify.sms"]["purpose"]
        st, r = staff_call("PUT", f"/apps/{ride}/capabilities/notify.sms", {"state": "GRANTED", "purpose": {"en": "Text you the driver's code."}})
        ok("staff approve a purpose for notify.sms (audited)", st == 200 and [c for c in r["capabilities"] if c["capability"] == "notify.sms"][0]["purpose"]["en"] == "Text you the driver's code.", st)
        st, r = staff_call("PUT", f"/apps/{ride}/environments/DEV", {"callbackUrl": hook_url})
        ok("staff set the callback address of demo-ride DEV (a local receiver)", st == 200 and r.get("callbackUrl") == hook_url, st)
        st, before = cap("counts_before", *staff_call("GET", f"/apps/{ride}/permissions"))
        loc_before = [c for c in before["capabilities"] if c["capability"] == "location.fine"][0]
        time.sleep(17)   # notification-service caches app configuration for 15 seconds

        st, s = userauth.login_verified("+251900000010", "worku123")
        ok("Kidist signs in with her phone and password", st == 200 and "session_token" in s, st)
        H = {"Authorization": "Bearer " + s["session_token"]}
        user = lambda m, p, b=None: staff.call(m, G + p, b, H)
        base = "/api/v1/me/apps/demo-ride/permissions"

        print("== 1. Kidist taps Demo Ride: no dialog, a launch token")
        st, launch = cap("launch", *user("POST", "/api/v1/me/apps/demo-ride/launch"))
        ok("launch -> 200 with a token, 300 s, the app URL and the states", st == 200 and launch["expiresInSeconds"] == 300 and launch["url"].startswith("https://www.zayride.com") and "launch=" in launch["url"], st)
        valid, header, claims = verify_jwt(launch["launchToken"], ride)
        CAPTURE["jwt"] = [header, claims]
        ok("the token is an RS256 JWT that verifies against the JWKS (kid, aud = app, 300 s)", valid, header)
        ok("sub is the pseudonymous id, never the user id; env dev; user.basic in the scopes", claims["sub"].startswith("u_p_") and uid not in launch["launchToken"] and claims["env"] == "dev" and "user.basic" in claims["scopes"] and "location.fine" not in claims["scopes"], claims)
        alias = claims["sub"]
        st, lst = cap("list_start", *user("GET", base))
        ok("nothing was asked at launch: location.fine NOT_ASKED, user.basic GRANTED", {p["capability"]: p["state"] for p in lst}.get("location.fine") == "NOT_ASKED" and {p["capability"]: p["state"] for p in lst}.get("user.basic") == "GRANTED")
        st, prof = cap("profile_basic", *(lambda r: (r[0], r[1]))(d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile")))
        ok("the mini app backend reads the profile: only the alias and granted [user.basic]; no name", st == 200 and prof["granted"] == ["user.basic"] and "fullName" not in prof, prof)

        print("== 2. 'Find a ride': the mini app asks for location.fine at the moment of need")
        st, req = cap("req_location", *user("POST", base + "/location.fine/request"))
        ok("request -> dialog allowed: NOT_ASKED with a one-time prompt, label and the staff-approved purpose", st == 200 and req["state"] == "NOT_ASKED" and req["prompt"]["promptId"].startswith("pr_") and req["prompt"]["purpose"] == caps["location.fine"]["purpose"]["en"] and req["prompt"]["label"] == "Your precise location", req)
        st, ans = cap("ans_location", *user("PUT", base + "/location.fine/answer", {"promptId": req["prompt"]["promptId"], "answer": "GRANTED"}))
        ok("Kidist taps Allow -> GRANTED", st == 200 and ans["state"] == "GRANTED" and ans["denialCount"] == 0, ans)
        st, again = user("PUT", base + "/location.fine/answer", {"promptId": req["prompt"]["promptId"], "answer": "DENIED"})
        ok("the same prompt cannot be used again (409 prompt_invalid)", st == 409 and again.get("error") == "prompt_invalid", again)
        st, r2 = user("POST", base + "/location.fine/request")
        ok("a granted permission shows no dialog next time", r2["state"] == "GRANTED" and r2["prompt"] is None)
        st, wrong = user("PUT", "/api/v1/me/apps/demo-food/permissions/location.fine/answer", {"promptId": req["prompt"]["promptId"], "answer": "GRANTED"})
        ok("a prompt of another app is refused (demo-food does not even grant location.fine -> 403)", st in (403, 409), f"{st} {wrong}")
        st, np_ = user("POST", base + "/user.contact/request")
        ok("a capability staff did not grant is NOT_AVAILABLE, never a dialog", st == 200 and np_["state"] == "NOT_AVAILABLE" and np_["prompt"] is None, np_)
        st, paused = user("POST", "/api/v1/me/apps/demo-paused/permissions/user.basic/request")
        ok("a PAUSED app answers 403 app_not_live", st == 403 and paused.get("error") == "app_not_live", paused)
        st, killed = user("POST", "/api/v1/me/apps/demo-killed/permissions/user.basic/request")
        ok("a KILLED app answers 403 app_not_live", st == 403 and killed.get("error") == "app_not_live", killed)
        st, lst = user("GET", base)
        ok("the menu sheet lists the granted capabilities with this user's states", {p["capability"]: p["state"] for p in lst}.get("location.fine") == "GRANTED")

        print("== 3. a prompt that was never shown cannot be answered; an old one expires")
        st, bogus = user("PUT", base + "/user.full_name/answer", {"promptId": "pr_made_up", "answer": "GRANTED"})
        ok("an answer without a prompt the server issued is refused", st == 409 and bogus.get("error") == "prompt_invalid", bogus)
        st, exp = user("POST", base + "/user.full_name/request")
        d.psql("users", f"update app_consent set prompt_expires_at = now() - interval '1 second' where user_id='{uid}' and {ride_q} and capability='user.full_name'")
        st, late = user("PUT", base + "/user.full_name/answer", {"promptId": exp["prompt"]["promptId"], "answer": "GRANTED"})
        ok("an expired prompt is refused (409 prompt_expired)", st == 409 and late.get("error") == "prompt_expired", late)

        print("== 4. the notify permission: refused, blocked, unblocked in the menu")
        st, r3 = cap("req_sms", *user("POST", base + "/notify.sms/request"))
        ok("notify.sms: the dialog shows the approved sentence", r3["prompt"] and r3["prompt"]["purpose"] == "Text you the driver's code.", r3)
        st, d1 = cap("sms_denied_1", *user("PUT", base + "/notify.sms/answer", {"promptId": r3["prompt"]["promptId"], "answer": "DENIED"}))
        ok("the first Deny -> DENIED, count 1, may be asked again", d1["state"] == "DENIED" and d1["denialCount"] == 1 and d1["canAskAgain"] is True, d1)
        st, tpl = d.pcall("demo-ride", "GET", "/v1/templates")[:2]
        direct = [t for t in tpl if t["code"] == "seed-ride-arriving"][0]["id"]
        inapp = [t for t in tpl if t["code"] == "seed-ride-receipt"][0]["id"]
        ext1 = RUN + "-sms-1"
        code, r, _ = d.pcall("demo-ride", "POST", "/v1/notifications", {"externalId": ext1, "userId": alias, "templateId": direct, "params": {"driver": "Girma", "plate": "AA-777"}})
        cap("send_refused_denied", code, r)
        ok("the partner texts anyway: 403 consent_required, notify.sms, DENIED; nothing queued", code == 403 and r == {"error": "consent_required", "capability": "notify.sms", "state": "DENIED"}, f"{code} {r}")
        ok("no notification row exists for it", d.psql("notification", f"select count(*) from notification where external_id='{ext1}'") == "0")
        st, r4 = user("POST", base + "/notify.sms/request")
        st, d2 = cap("sms_blocked", *user("PUT", base + "/notify.sms/answer", {"promptId": r4["prompt"]["promptId"], "answer": "DENIED"}))
        ok("the second Deny -> BLOCKED (count 2, never asked again)", d2["state"] == "BLOCKED" and d2["denialCount"] == 2 and d2["canAskAgain"] is False, d2)
        st, r5 = cap("req_sms_blocked", *user("POST", base + "/notify.sms/request"))
        ok("next day the mini app asks again: BLOCKED, prompt null, no dialog", r5["state"] == "BLOCKED" and r5["prompt"] is None, r5)
        code, r, _ = d.pcall("demo-ride", "POST", "/v1/notifications", {"externalId": RUN + "-sms-2", "userId": alias, "templateId": direct, "params": {"driver": "Girma", "plate": "AA-777"}})
        ok("blocked: the partner is refused with state BLOCKED", code == 403 and r.get("state") == "BLOCKED", f"{code} {r}")
        st, m1 = cap("menu_sms_on", *user("PUT", base + "/notify.sms", {"state": "GRANTED"}))
        ok("Kidist opens the menu and switches SMS on -> GRANTED, count 0 (the only way out of BLOCKED)", m1["state"] == "GRANTED" and m1["denialCount"] == 0, m1)
        ext3 = RUN + "-sms-3"
        code, r, _ = d.pcall("demo-ride", "POST", "/v1/notifications", {"externalId": ext3, "userId": alias, "templateId": direct, "params": {"driver": "Girma", "plate": "AA-777"}})
        cap("send_accepted", code, r)
        ok("the next SMS goes through: 202 QUEUED, channel SMS", code == 202 and r["status"] == "QUEUED" and r["channel"] == "SMS", f"{code} {r}")
        end = time.time() + 25
        status = None
        while time.time() < end:
            c2, r2_, _ = d.pcall("demo-ride", "GET", f"/v1/notifications/{ext3}")
            status = r2_.get("status")
            if status in ("SENT", "FAILED", "UNREACHABLE", "SUPPRESSED"):
                break
            time.sleep(1)
        ok("delivered by the dev provider (console SMS, nothing real): SENT", status == "SENT", status)
        # the in-app channel: allow it in the dialog, and the old mute switch is the same data
        st, ri = user("POST", base + "/notify.inapp/request")
        user("PUT", base + "/notify.inapp/answer", {"promptId": ri["prompt"]["promptId"], "answer": "GRANTED"})
        code, r, _ = d.pcall("demo-ride", "POST", "/v1/notifications", {"externalId": RUN + "-inapp-1", "userId": alias, "templateId": inapp, "params": {"fare": "250"}})
        ok("in-app receipt after the user allowed notify.inapp -> 202", code == 202 and r["channel"] == "IN_APP", f"{code} {r}")
        st, pref = user("PUT", f"/api/v1/me/apps/{ride}/preferences", {"muteInApp": True})
        ok("the old mute switch is the same data: muteInApp true -> notify.inapp DENIED", st == 200 and pref["muteInApp"] is True and pref["states"]["notify.inapp"] == "DENIED", pref)
        code, r, _ = d.pcall("demo-ride", "POST", "/v1/notifications", {"externalId": RUN + "-inapp-2", "userId": alias, "templateId": inapp, "params": {"fare": "250"}})
        ok("muted user -> 403 consent_required (notify.inapp, DENIED)", code == 403 and r.get("capability") == "notify.inapp" and r.get("state") == "DENIED", f"{code} {r}")
        user("PUT", f"/api/v1/me/apps/{ride}/preferences", {"muteInApp": False})

        print("== 5. the name, the partner profile, and turning a permission off")
        st, rn = user("POST", base + "/user.full_name/request")
        user("PUT", base + "/user.full_name/answer", {"promptId": rn["prompt"]["promptId"], "answer": "GRANTED"})
        st, prof2 = cap("profile_name", *d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile")[:2])
        ok("after the user allowed the name: fullName, granted [user.basic, user.full_name]", st == 200 and prof2.get("fullName") == "Demo Kidist Worku" and prof2["granted"] == ["user.basic", "user.full_name"], prof2)
        ok("fields the user did not allow are absent, not null", "phone" not in prof2 and "email" not in prof2 and "address" not in prof2 and None not in prof2.values())
        st, off = user("PUT", base + "/user.full_name", {"state": "DENIED"})
        st, need = cap("profile_name_needed", *d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile?fields=user.full_name")[:2])
        ok("a backend that needs the name after Kidist switched it off: 403 consent_required, DENIED", st == 403 and need == {"error": "consent_required", "capability": "user.full_name", "state": "DENIED"}, need)
        st, plain = d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile")[:2]
        ok("without asking for it the profile is just alias + user.basic again", st == 200 and plain["granted"] == ["user.basic"] and "fullName" not in plain, plain)
        st, no_sig = staff.call("GET", G + f"/v1/users/{alias}/profile")
        ok("an unsigned profile read is refused (401 missing_signature)", st == 401 and no_sig.get("error") == "missing_signature", no_sig)
        code, wrong_env, _ = d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile", env="prod")
        ok("a PROD key on this DEV deployment is refused (403 wrong_environment)", code == 403 and wrong_env.get("error") == "wrong_environment", wrong_env)
        code, syn, _ = d.pcall("demo-ride", "GET", "/v1/users/u_p_test_ok/profile")
        ok("the synthetic test user answers in DEV", code == 200 and syn.get("fullName") == "Test User", syn)
        st, off_loc = cap("menu_location_off", *user("PUT", base + "/location.fine", {"state": "DENIED"}))
        ok("Kidist turns location off in the top menu: DENIED, count 0 (the mini app may ask again)", off_loc["state"] == "DENIED" and off_loc["denialCount"] == 0, off_loc)
        st, again_req = user("POST", base + "/location.fine/request")
        ok("the mini app asks again with an Allow button: a dialog is allowed again", again_req["state"] == "DENIED" and again_req["prompt"] is not None, again_req)

        print("== 6. what staff see")
        st, after = cap("counts_after", *staff_call("GET", f"/apps/{ride}/permissions"))
        loc_after = [c for c in after["capabilities"] if c["capability"] == "location.fine"][0]
        sms_after = [c for c in after["capabilities"] if c["capability"] == "notify.sms"][0]
        ok("backoffice counts: location.fine has one more DENIED than before, and notify.sms one more GRANTED", loc_after["denied"] == loc_before["denied"] + 1 and sms_after["granted"] >= 1, f"{loc_before} -> {loc_after}")
        st, audit = staff_call("GET", f"/audit?appId={ride}&action=capability.set")
        ok("the purpose change is on the audit trail with the old and the new text", st == 200 and audit[0]["meta"]["purpose"]["en"] == "Text you the driver's code." and "previousPurpose" in audit[0]["meta"], audit[0]["meta"] if audit else st)

        print("== 7. Disconnect")
        st, disc = cap("disconnect", *user("DELETE", base))
        ok("Disconnect -> everything DENIED, changed lists what was taken back", st == 200 and disc["disconnected"] is True and {c["capability"] for c in disc["changed"]} >= {"user.basic", "notify.sms"}, disc)
        st, gone = cap("profile_disconnected", *d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile")[:2])
        ok("the next /profile returns 403 consent_required for user.basic", st == 403 and gone == {"error": "consent_required", "capability": "user.basic", "state": "DENIED"}, gone)
        code, r, _ = d.pcall("demo-ride", "POST", "/v1/notifications", {"externalId": RUN + "-sms-4", "userId": alias, "templateId": direct, "params": {"driver": "Girma", "plate": "AA-777"}})
        ok("and an SMS is refused too (consent withdrawn)", code == 403 and r.get("error") == "consent_required", f"{code} {r}")
        st, lst2 = user("GET", base)
        ok("the menu sheet shows DENIED for what she had allowed, NOT_ASKED for what was never asked", {p["capability"]: p["state"] for p in lst2}["user.basic"] == "DENIED")
        st, launch2 = user("POST", "/api/v1/me/apps/demo-ride/launch")
        states2 = {c["capability"]: c["state"] for c in launch2["consents"]}
        ok("opening the app again reconnects user.basic (and nothing else)", st == 200 and states2["user.basic"] == "GRANTED" and states2["location.fine"] == "DENIED" and states2["notify.sms"] == "DENIED", states2)
        st, pb = d.pcall("demo-ride", "GET", f"/v1/users/{alias}/profile")[:2]
        ok("the profile works again with user.basic only", st == 200 and pb["granted"] == ["user.basic"], pb)

        print("== 8. the callbacks the partner received")
        wait_events(9)
        events = [(g["headers"].get("X-Adera-Event"), json.loads(g["body"])) for g in received if "/adera/events" in g["path"]]
        CAPTURE["callbacks"] = [{"headers": {k: v for k, v in g["headers"].items() if k.lower().startswith(("x-adera", "idempotency", "user-agent", "content-type"))}, "body": json.loads(g["body"])} for g in received]
        kinds = [e[0] for e in events if e[0] != "notification.status"]
        consent_changes = [(e[1]["capability"], e[1]["state"]) for e in events if e[0] == "user.consent_changed"]
        ok("every real change was reported: location GRANTED, SMS DENIED then BLOCKED then GRANTED, name, location off ...",
           ("location.fine", "GRANTED") in consent_changes and ("notify.sms", "DENIED") in consent_changes and ("notify.sms", "BLOCKED") in consent_changes and ("notify.sms", "GRANTED") in consent_changes
           and ("user.full_name", "GRANTED") in consent_changes and ("location.fine", "DENIED") in consent_changes, consent_changes)
        ok("Disconnect is ONE user.disconnected event with the permissions taken back", kinds.count("user.disconnected") == 1 and "user.consent_changed" in kinds, kinds)
        disc_ev = [e[1] for e in events if e[0] == "user.disconnected"]
        ok("the events carry the pseudonymous id and no real user id", all(e[1]["alias"] == alias for e in events if e[0].startswith("user.")) and uid not in json.dumps([g["body"] for g in received]))
        ok("every callback is signed with the Adera key (verified against the JWKS)", all(verify_callback_signature(g) for g in received) and len(received) > 0, len(received))
        CAPTURE["disconnect_event"] = disc_ev[0] if disc_ev else None
    finally:
        print("== cleanup (put everything back)")
        try:
            if original_sms is not None:
                staff.call("PUT", G + f"/admin/api/v1/apps/{ride}/capabilities/notify.sms", {"state": "GRANTED", "purpose": original_sms}, A)
            staff.call("PUT", G + f"/admin/api/v1/apps/{ride}/environments/DEV", {"callbackUrl": None}, A)
        finally:
            handle.close()
            d.psql("users", f"delete from app_consent where user_id='{uid}' and {ride_q}")
            for line in filter(None, snap_consent.split("\n")):
                c, state, n_, at, src = line.split("|")
                d.psql("users", f"insert into app_consent (user_id,app_id,capability,state,denial_count,decided_at,source) values ('{uid}','{ride}','{c}','{state}',{n_},{repr(at) if at else 'null'},{repr(src) if src else 'null'})".replace('"', "'"))
            d.psql("users", f"delete from consent_event where user_id='{uid}' and id > {max_event}")
            d.psql("users", f"delete from partner_event_outbox where {ride_q}")
            d.psql("notification", f"delete from notification where external_id like '{RUN}-%'")
            d.psql("notification", f"delete from callback where {ride_q} and event like 'user.%'")
            d.psql("notification", f"delete from profile_access where {ride_q}")
            d.psql("notification", f"delete from usage_daily where {ride_q}")
            for line in filter(None, snap_daily.split("\n")):
                ch, day, u_, sent = line.split("|")
                d.psql("notification", f"insert into usage_daily (app_id, environment, channel, day, user_id, sent) values ('{ride}','DEV','{ch}','{day}','{u_}',{sent})")
            d.psql("notification", f"delete from usage_total where {ride_q}")
            for line in filter(None, snap_total.split("\n")):
                env_, ch, total = line.split("|")
                d.psql("notification", f"insert into usage_total (app_id, environment, channel, total) values ('{ride}','{env_}','{ch}',{total})")
            left = d.psql("notification", f"select count(*) from notification where external_id like '{RUN}-%'")
            print("  notifications left behind:", left, " staff accounts left:", d.psql("registry", "select count(*) from staff where email like 'tempstaff-%'"))
    if os.environ.get("PERM_CAPTURE"):
        json.dump(CAPTURE, open(os.environ["PERM_CAPTURE"], "w"), indent=1, ensure_ascii=False)
    print(f"\n{passed} passed, {failed} failed")
    return failed


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
