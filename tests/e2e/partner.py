"""Partner platform end to end, through the GATEWAY, with every real service (Kratos, registry, user, notification):
staff creates a mini app, grants notification capabilities, sets limits and the allowed server address, registers the
partner's PUBLIC key, creates a template; a signed partner request is accepted, delivered (dev SMS), reported back by a
signed callback, and the negative cases (bad signature, replay, quota, wrong address) behave. Cleans up after itself.
Needs DEV mode and PARTNER_ALLOW_PRIVATE_CALLBACKS=true (the callback receiver is on localhost).
Run from the repo root:  python3 -I tests/e2e/partner.py"""
import base64, hashlib, json, os, re, struct, subprocess, sys, tempfile, threading, time, urllib.error, urllib.parse, urllib.request, uuid, zlib
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import staff, tempstaff

G = staff.G; K = staff.K; N = staff.N
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
passed = failed = 0


def ok(label, cond, extra=""):
    global passed, failed
    passed, failed = (passed + 1, failed) if cond else (passed, failed + 1)
    print(("  PASS " if cond else "  FAIL ") + label + (f"  [{extra}]" if extra else ""))
    return cond


def env_value(name):
    for line in open(os.path.join(ROOT, ".env")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()


def psql(db, sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "adera_admin", "-d", db, "-Atc", sql], capture_output=True, text=True, cwd=ROOT).stdout.strip()


def png(w, h):
    rows = b"".join(b"\x00" + bytes([30, 120, 200]) * w for _ in range(h))
    def chunk(t, d): c = struct.pack(">I", len(d)) + t + d; return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def multipart(content):
    b = "----adera" + uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="logo.png"\r\nContent-Type: image/png\r\n\r\n').encode() + content + f"\r\n--{b}--\r\n".encode()
    return body, f"multipart/form-data; boundary={b}"


def raw(method, url, data=None, headers=None):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers or {}), timeout=30)
        return r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


def user_signup(phone):
    staff.call("DELETE", N + "/dev/messages")
    _, f = staff.call("GET", K + "/self-service/registration/api")
    staff.call("POST", f"{K}/self-service/registration?flow={f['id']}", {"method": "code", "traits.phone_number": phone})
    code = None
    for _ in range(15):
        _, m = staff.call("GET", f"{N}/dev/messages?to={urllib.parse.quote(phone)}")
        if isinstance(m, list) and m:
            code = re.search(r"\b(\d{6})\b", m[0]["body"]).group(1); break
        time.sleep(1)
    _, r = staff.call("POST", f"{K}/self-service/registration?flow={f['id']}", {"method": "code", "traits.phone_number": phone, "code": code})
    return r["session_token"], r["identity"]["id"]


# ---- partner side: an RSA key pair made with openssl; only the public half is ever given to us -----------------------
tmp = tempfile.mkdtemp()
priv, pub = os.path.join(tmp, "partner.pem"), os.path.join(tmp, "partner.pub")
subprocess.run(["openssl", "genrsa", "-out", priv, "2048"], check=True, capture_output=True)
subprocess.run(["openssl", "rsa", "-in", priv, "-pubout", "-out", pub], check=True, capture_output=True)
PUBLIC_PEM = open(pub).read()


def rs256(canonical):
    out = subprocess.run(["openssl", "dgst", "-sha256", "-sign", priv], input=canonical.encode(), capture_output=True, check=True).stdout
    return base64.b64encode(out).decode()


def partner_call(key_id, method, path, body=None, ts=None, tamper=False):
    data = json.dumps(body).encode() if body is not None else b""
    ts = str(ts or int(time.time()))
    canonical = f"ADERA-SIG-1\n{ts}\n{method}\n{path}\n{base64.b64encode(hashlib.sha256(data).digest()).decode()}"
    sig = rs256(canonical)
    if tamper:
        data = data.replace(b"Abebe", b"Hacker")
    h = {"X-Adera-Key-Id": key_id, "X-Adera-Timestamp": ts, "X-Adera-Signature": sig, "Content-Type": "application/json"}
    code, resp, hdrs = raw(method, G + path, data or None, h)
    try:
        return code, json.loads(resp or b"{}"), hdrs, sig, resp
    except Exception:
        return code, {"raw": resp[:100].decode(errors="replace")}, hdrs, sig, resp


# ---- a callback receiver standing in for the partner's server -------------------------------------------------------
received = []


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        received.append((dict(self.headers), body))
        self.send_response(200); self.end_headers()

    def log_message(self, *a):
        pass


hook = HTTPServer(("0.0.0.0", 0), Hook)
threading.Thread(target=hook.serve_forever, daemon=True).start()
def host_ip():
    """Address of this machine as seen from the compose network (the bridge gateway)."""
    net = subprocess.run(["docker", "compose", "ps", "-q", "notification-service"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
    out = subprocess.run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}", net], capture_output=True, text=True).stdout.strip()
    return out or "172.17.0.1"


hook_url = f"http://{host_ip()}:{hook.server_address[1]}/hook"

handle = tempstaff.script_session("ADMIN")  # a temporary staff account (removed at exit); ADERA_STAFF_EMAIL uses a real one
A = {"Authorization": "Bearer " + handle.token}
def admin(method, path, body=None): return staff.call(method, G + "/admin/api/v1" + path, body, A)

slug = "e2e-partner-" + uuid.uuid4().hex[:6]
app_id = None; user_id = None
try:
    print("setup (staff, through the gateway)")
    st, app = admin("POST", "/apps", {"slug": slug, "name": {"en": "E2E Partner"}, "supportEmail": "support@example.com"})
    ok("app created", st in (200, 201), st); app_id = app.get("id")
    body, ctype = multipart(png(256, 256))
    st, _, _ = raw("POST", f"{G}/admin/api/v1/apps/{app_id}/logo", body, {**A, "Content-Type": ctype})
    ok("logo uploaded", st == 200, st)
    for cap in ("notify.sms", "notify.email", "notify.inapp"):
        st, _ = admin("PUT", f"/apps/{app_id}/capabilities/{cap}", {"state": "GRANTED"})
        ok(f"capability {cap} granted", st == 200, st)
    st, r = admin("POST", f"/apps/{app_id}/status", {"status": "LIVE"}); ok("app set LIVE", st == 200, st)
    st, r = admin("PUT", f"/apps/{app_id}/notification-limits/SMS", {"maxPerUserPerDay": 2, "maxPerDay": 100, "maxDistinctUsersPerDay": 100, "maxTotal": 1000})
    ok("SMS limit: 2 per user per day", st == 200, st)
    st, r = admin("GET", "/system")
    ok("this deployment runs DEV (GET /system)", st == 200 and r.get("environment") == "DEV", f"{st} {r}")
    st, r = admin("POST", f"/apps/{app_id}/keys", {"environment": "DEV", "publicKeyPem": PUBLIC_PEM, "label": "e2e"})
    ok("partner PUBLIC key registered (DEV)", st == 200 and r.get("keyId", "").startswith("ak_dev_"), st); key_id = r.get("keyId")
    st, r = admin("POST", f"/apps/{app_id}/keys", {"environment": "PROD", "publicKeyPem": PUBLIC_PEM, "label": "e2e prod"})
    ok("a PROD key can be registered too (stored, not active here)", st == 200 and r.get("keyId", "").startswith("ak_prod_"), st); prod_key_id = r.get("keyId")
    st, r = admin("POST", f"/apps/{app_id}/keys", {"environment": "DEV", "publicKeyPem": open(priv).read()})
    ok("a PRIVATE key is refused", st == 400, st)
    st, r = admin("POST", f"/apps/{app_id}/keys", {"environment": "LIVE", "publicKeyPem": PUBLIC_PEM})
    ok("the old environment name LIVE is refused", st == 400, st)
    st, r = admin("PUT", f"/apps/{app_id}/environments/PROD", {"allowedCidrs": ["0.0.0.0/0"]})
    ok("PROD may not allow every address (/0)", st == 400, st)
    st, r = admin("PUT", f"/apps/{app_id}/environments/PROD", {"allowedCidrs": ["198.51.100.20"]})
    ok("PROD addresses can be stored while running DEV", st == 200, st)
    st, r = admin("PUT", f"/apps/{app_id}/environments/DEV", {"allowedCidrs": ["203.0.113.77"]})
    ok("allowed DEV server address set (not ours)", st == 200, st)
    st, tpl = staff.call("POST", G + "/admin/api/v1/notification/templates", {
        "appId": app_id, "code": "job-accepted", "kind": "DIRECT", "category": "TRANSACTIONAL",
        "params": [{"name": "name", "maxLength": 30}],
        "content": {"en": {"sms": "Hi {name}, your Adera job was accepted", "body": "Hi {name}", "emailSubject": "Job accepted"}}}, A)
    ok("template created by staff", st == 200 and tpl.get("id"), st); template_id = tpl.get("id")

    print("a real user")
    session, user_id = user_signup("+2519" + str(int(time.time()))[-8:])
    st, r = staff.call("POST", f"http://127.0.0.1:8086/internal/aliases", {"appId": app_id, "userId": user_id}, {"X-Internal-Secret": env_value("INTERNAL_API_SECRET")})
    ok("user's pseudonymous id for this app minted", st == 200 and str(r.get("alias", "")).startswith("u_p_"), st); alias = r.get("alias")
    ok("alias is not the user id", user_id not in json.dumps(r))

    print("partner requests")
    msg = {"externalId": "job-1", "userId": alias, "templateId": template_id, "params": {"name": "Abebe"}, "callbackUrl": hook_url}
    code, r, h, _, _ = partner_call(key_id, "POST", "/v1/notifications", msg)
    ok("from a server address that is not allowed -> 403 ip_not_allowed", code == 403 and r.get("error") == "ip_not_allowed", f"{code} {r}")
    # the gateway reports our real address: allow it
    admin("PUT", f"/apps/{app_id}/environments/DEV", {"allowedCidrs": ["127.0.0.1", "172.16.0.0/12", "192.168.0.0/16", "10.0.0.0/8"]})
    time.sleep(16)  # notification-service caches app config for 15 seconds
    code, r, h, rsig, rbody = partner_call(key_id, "POST", "/v1/notifications", msg)
    ok("signed request accepted -> 202 QUEUED", code == 202 and r.get("status") == "QUEUED", f"{code} {r}")
    ok("response carries our signature", bool(h.get("X-Adera-Signature")) and bool(h.get("X-Adera-Key-Id")))
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", msg)
    ok("same request again -> 200 replay", code == 200 and r.get("replayed") is True, f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {**msg, "params": {"name": "Other"}})
    ok("same externalId, different content -> 409", code == 409 and r.get("error") == "external_id_reused", f"{code} {r}")
    code, r, *_ = partner_call(prod_key_id, "POST", "/v1/notifications", {**msg, "externalId": "job-prod"})
    ok("a PROD key on this DEV deployment -> 403 wrong_environment", code == 403 and r.get("error") == "wrong_environment", f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", msg, tamper=True)
    ok("body changed after signing -> 401", code == 401 and r.get("error") == "invalid_signature", f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", msg, ts=int(time.time()) - 3600)
    ok("old timestamp -> 401", code == 401 and r.get("error") == "stale_timestamp", f"{code} {r}")
    code, r, *_ = partner_call("pk_unknown", "POST", "/v1/notifications", msg)
    ok("unknown key -> 401", code == 401, f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {**msg, "externalId": "job-x", "userId": "u_p_doesnotexist"})
    ok("unknown user -> generic 422 user_unreachable", code == 422 and r.get("error") == "user_unreachable", f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {**msg, "externalId": "job-y", "params": {"name": "see http://evil.example"}})
    ok("link inside a value -> refused", code in (400, 422), f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {**msg, "externalId": "job-cb", "callbackUrl": "https://user:pw@example.com/hook"})
    ok("callback URL with credentials -> 400 at once (not dropped later)", code == 400 and "callbackUrl" in r.get("fields", {}), f"{code} {r}")

    print("synthetic test users (DEV only)")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {"externalId": "synth-1", "userId": "u_p_test_ok", "templateId": template_id, "params": {"name": "Test"}})
    ok("u_p_test_ok accepted in DEV -> 202", code == 202, f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {"externalId": "synth-2", "userId": "u_p_test_unreachable", "templateId": template_id, "params": {"name": "Test"}})
    ok("u_p_test_unreachable -> 422 user_unreachable", code == 422 and r.get("error") == "user_unreachable", f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {"externalId": "synth-3", "userId": "u_p_test_muted", "templateId": template_id, "params": {"name": "Test"}})
    ok("u_p_test_muted -> 200 SUPPRESSED", code == 200 and r.get("status") == "SUPPRESSED", f"{code} {r}")
    for _ in range(20):
        code, r, *_ = partner_call(key_id, "GET", "/v1/notifications/synth-1")
        if r.get("status") == "SENT": break
        time.sleep(1)
    ok("u_p_test_ok is SENT by simulation", r.get("status") == "SENT", r)
    ok("simulation recorded provider 'sandbox' (nothing was sent)",
       psql("notification", f"SELECT a.provider FROM notification_attempt a JOIN notification n ON n.id = a.notification_id WHERE n.app_id='{app_id}' AND n.external_id='synth-1'") == "sandbox")

    print("delivery (background worker, dev SMS)")
    for _ in range(20):
        code, r, *_ = partner_call(key_id, "GET", "/v1/notifications/job-1")
        if r.get("status") == "SENT": break
        time.sleep(1)
    ok("status becomes SENT", r.get("status") == "SENT" and r.get("channel") == "SMS", r)
    logs = subprocess.run(["docker", "compose", "logs", "notification-service", "--since", "2m"], capture_output=True, text=True, cwd=ROOT).stdout
    ok("dev SMS (console log) shows the filled-in template", "body=Hi Abebe, your Adera job was accepted" in logs)

    print("signed callback")
    for _ in range(20):
        if received: break
        time.sleep(1)
    ok("callback received", bool(received))
    if received:
        hdr, body = received[0]
        hdr = {k.lower(): v for k, v in hdr.items()}
        ok("callback names the notification and its final status", b"job-1" in body and b"SENT" in body, body[:120])
        ok("callback is signed with our key", bool(hdr.get("x-adera-signature")) and bool(hdr.get("x-adera-key-id")))
        st, jwks, _ = raw("GET", G + "/.well-known/jwks.json")
        ok("our public key is published (JWKS)", st == 200 and b'"RSA"' in jwks)
        canonical = f"ADERA-SIG-1\n{hdr['x-adera-timestamp']}\nPOST\n/hook\n{base64.b64encode(hashlib.sha256(body).digest()).decode()}"
        key = json.loads(jwks)["keys"][0]
        def b64u(s): return int.from_bytes(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)), "big")
        n, e = b64u(key["n"]), b64u(key["e"])
        sig = int.from_bytes(base64.b64decode(hdr["x-adera-signature"]), "big")
        recovered = pow(sig, e, n).to_bytes((n.bit_length() + 7) // 8, "big")
        digest = hashlib.sha256(canonical.encode()).digest()
        ok("callback signature verifies against the published key (RS256)", recovered.endswith(digest))

    print("limits")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {k: v for k, v in {**msg, "externalId": "job-2"}.items() if k != "callbackUrl"})
    ok("second SMS to the same user today -> 202 (limit is 2)", code == 202, f"{code} {r}")
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {k: v for k, v in {**msg, "externalId": "job-3"}.items() if k != "callbackUrl"})
    ok("third SMS to the same user today -> 429 quota_exceeded", code == 429 and r.get("error") == "quota_exceeded", f"{code} {r}")

    print("in-app notification and inbox")
    st, tpl2 = staff.call("POST", G + "/admin/api/v1/notification/templates", {
        "appId": app_id, "code": "ride-here", "kind": "IN_APP", "category": "TRANSACTIONAL", "params": [{"name": "name", "maxLength": 30}],
        "content": {"en": {"title": "Hello {name}", "body": "Your ride is here"}}}, A)
    code, r, *_ = partner_call(key_id, "POST", "/v1/notifications", {"externalId": "inapp-1", "userId": alias, "templateId": tpl2.get("id"), "params": {"name": "Abebe"}})
    ok("IN_APP accepted", code == 202, f"{code} {r}")
    time.sleep(4)
    st, inbox = staff.call("GET", G + "/api/v1/me/notifications", None, {"Authorization": "Bearer " + session})
    ok("shows in the user's inbox", st == 200 and isinstance(inbox, list) and any(i.get("title") == "Hello Abebe" for i in inbox), f"{st} {inbox}")
    st, c = staff.call("GET", G + "/api/v1/me/notifications/unread-count", None, {"Authorization": "Bearer " + session})
    ok("unread count is 1", st == 200 and c.get("count") == 1, c)

    print("staff visibility")
    st, rows = staff.call("GET", f"{G}/admin/api/v1/notification/notifications?appId={app_id}", None, A)
    ok("staff can list what the app sent", st == 200 and len(rows) >= 3, st)
    st, rows = staff.call("GET", f"{G}/admin/api/v1/notification/usage?appId={app_id}&environment=DEV", None, A)
    ok("staff can see usage against limits", st == 200, st)
    st, rows2 = staff.call("GET", f"{G}/admin/api/v1/notification/usage?appId={app_id}", None, A)
    ok("usage defaults to the deployment's environment", st == 200 and rows2 == rows, st)
    st, rows = staff.call("GET", f"{G}/admin/api/v1/notification/notifications?appId={app_id}&environment=DEV", None, A)
    ok("notification list filter environment=DEV", st == 200 and len(rows) >= 3 and all(x["environment"] == "DEV" for x in rows), st)
    st, _ = staff.call("GET", f"{G}/admin/api/v1/notification/templates?appId={app_id}", None)
    ok("no staff session -> 401", st == 401, st)
finally:
    print("cleanup")
    if app_id:
        psql("notification", f"DELETE FROM callback WHERE notification_id IN (SELECT id FROM notification WHERE app_id='{app_id}')")
        psql("notification", f"DELETE FROM notification_attempt WHERE notification_id IN (SELECT id FROM notification WHERE app_id='{app_id}')")
        for t in ("inbox_item", "usage_daily", "usage_total", "notification", "template"):
            psql("notification", f"DELETE FROM {t} WHERE app_id='{app_id}'")
        psql("registry", f"DELETE FROM mini_app WHERE id='{app_id}'")
    if user_id:
        subprocess.run(["curl", "-s", "-X", "DELETE", f"http://127.0.0.1:4434/admin/identities/{user_id}"], capture_output=True)
        psql("users", f"DELETE FROM app_user_alias WHERE user_id='{user_id}'")
        psql("users", f"DELETE FROM device WHERE user_id='{user_id}'")
        psql("users", f"DELETE FROM app_notification_preference WHERE user_id='{user_id}'")
        psql("users", f"DELETE FROM app_user WHERE id='{user_id}'")
    hook.shutdown()
    handle.close()
print(f"\n{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
