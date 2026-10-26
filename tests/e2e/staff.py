"""Dashboard staff login through the gateway, as a script (and a module the other e2e scripts import).

   login with an emailed one-time code  ->  (aal1: admin API refuses with mfa_required)
   enrol an authenticator app (TOTP)    ->  upgrade the session with a TOTP code  ->  (aal2: admin API works)

Needs the stack in DEV mode (the one-time code is read from notification-service's dev endpoint).
Run:  python3 -I tests/e2e/staff.py [email]     (default: BOOTSTRAP_SUPER_ADMIN_EMAIL from .env)
The TOTP secret is kept in tests/e2e/.staff-totp-<email>.json (gitignored) so later runs can sign in again."""
import base64, hashlib, hmac, json, os, re, struct, sys, time, urllib.error, urllib.parse, urllib.request

G = "http://127.0.0.1:9080"; K = G + "/kratos"; N = "http://127.0.0.1:8085"
HERE = os.path.dirname(os.path.abspath(__file__))


def call(method, url, body=None, headers=None):
    h = {"accept": "application/json", **(headers or {})}
    data = None
    if body is not None:
        data = json.dumps(body).encode(); h["content-type"] = "application/json"
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h), timeout=30)
        raw, code = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read(), e.code
    try:
        return code, json.loads(raw or b"{}")
    except Exception:
        return code, {"raw": raw[:200].decode(errors="replace")}


def totp(secret_b32, at=None):
    key = base64.b32decode(secret_b32.upper() + "=" * (-len(secret_b32) % 8))
    counter = int((at or time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    off = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[off:off + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def wait_for_next_window():
    """Kratos does not accept the same TOTP code twice, so use a fresh 30-second window."""
    time.sleep(30 - (time.time() % 30) + 1)


def otp_from_dev_endpoint(email, tries=20):
    for _ in range(tries):
        _, m = call("GET", f"{N}/dev/messages?to={urllib.parse.quote(email)}")
        if isinstance(m, list) and m:
            hit = re.search(r"\b(\d{6})\b", (m[0].get("subject") or "") + " " + (m[0].get("body") or ""))
            if hit:
                return hit.group(1)
        time.sleep(1)
    raise SystemExit("no one-time code arrived for " + email)


def email_login(email):
    """First step: emailed code. Returns an aal1 session token."""
    call("DELETE", N + "/dev/messages")
    _, f = call("GET", K + "/self-service/login/api")
    st, r = call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "code", "identifier": email})
    if st == 429:
        raise SystemExit("rate limited by the gateway (10 login submits/min per IP): wait a minute and retry")
    code = otp_from_dev_endpoint(email)
    st, r = call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "code", "identifier": email, "code": code})
    if not r.get("session_token"):
        raise SystemExit(f"email login failed ({st}): {json.dumps(r)[:300]}")
    return r["session_token"]


def enrol_totp(token):
    """Enrol an authenticator app on this (freshly logged in) session. Returns the base32 secret."""
    auth = {"Authorization": "Bearer " + token}
    _, f = call("GET", K + "/self-service/settings/api", headers=auth)
    secret = None
    for n in f.get("ui", {}).get("nodes", []):
        if n["attributes"].get("id") == "totp_secret_key":
            secret = n["attributes"]["text"]["text"]
    if not secret:
        raise SystemExit("no TOTP secret in the settings flow: " + json.dumps(f)[:400])
    st, r = call("POST", f"{K}/self-service/settings?flow={f['id']}", {"method": "totp", "totp_code": totp(secret)}, headers=auth)
    if st not in (200, 303) and r.get("state") != "success":
        raise SystemExit(f"TOTP enrolment failed ({st}): {json.dumps(r)[:400]}")
    return secret


def upgrade_to_aal2(token, secret):
    auth = {"Authorization": "Bearer " + token}
    st, f = call("GET", K + "/self-service/login/api?aal=aal2&refresh=false", headers={"X-Session-Token": token})
    if "id" not in f:
        raise SystemExit(f"could not start the aal2 flow ({st}): {json.dumps(f)[:400]}")
    st, r = call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "totp", "totp_code": totp(secret)}, headers={"X-Session-Token": token})
    new_token = r.get("session_token") or token
    if st != 200:
        raise SystemExit(f"aal2 upgrade failed ({st}): {json.dumps(r)[:400]}")
    return new_token


def staff_session(email, state_dir=HERE):
    """A ready-to-use aal2 session token for a staff member (enrols an authenticator the first time)."""
    path = os.path.join(state_dir, ".staff-totp-" + re.sub(r"[^a-z0-9]", "_", email.lower()) + ".json")
    token = email_login(email)
    if os.path.exists(path):
        secret = json.load(open(path))["secret"]
    else:
        secret = enrol_totp(token)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump({"email": email, "secret": secret}, fh)
        wait_for_next_window()
    return upgrade_to_aal2(token, secret)


if __name__ == "__main__":
    email = sys.argv[1] if len(sys.argv) > 1 else None
    if not email:
        for line in open(os.path.join(HERE, "..", "..", ".env")):
            if line.startswith("BOOTSTRAP_SUPER_ADMIN_EMAIL="):
                email = line.split("=", 1)[1].strip()
    print("staff:", email)
    t1 = email_login(email)
    st, r = call("GET", G + "/admin/api/v1/me", headers={"Authorization": "Bearer " + t1})
    print("1. after the emailed code only (aal1): admin API ->", st, r.get("error"))
    token = staff_session(email)
    st, r = call("GET", G + "/admin/api/v1/me", headers={"Authorization": "Bearer " + token})
    print("2. after the authenticator step (aal2): admin API ->", st, r)
    sys.exit(0 if st == 200 else 1)
