"""Registers the Bridge Lab mini app on the DEV stack through the real admin API, with a TEMPORARY ADMIN
(tests/e2e/tempstaff.py: a random tempstaff-xxxx@adera.local account removed at exit; never admin@adera.local).

Idempotent: every step reads first and only changes what differs.
  1. the app "bridge-lab" (created if missing; logo + support email so it may go LIVE)
  2. entryUrl and allowedOrigins
  3. EVERY capability of GET /admin/api/v1/capabilities granted, with a purpose sentence
  4. DEV environment: allowed caller addresses (docker ranges) and the callback URL
  5. an RSA key pair in bridge-lab/.local/ (the private half stays there) and the public half registered for DEV
  6. two approved notification templates (IN_APP and DIRECT)
  7. status LIVE (a KILLED app comes back only with --revive, which uses a temporary SUPER_ADMIN)
  8. bridge-lab/.local/config.json for the container

Run from anywhere:  python3 -I bridge-lab/seed.py [--entry-url URL] [--alt-origin ORIGIN] [--callback-url URL] [--revive]
Needs the dev stack (gateway 127.0.0.1:9080, Kratos admin 127.0.0.1:4434, docker compose exec postgres) and openssl."""
import argparse, base64, hashlib, json, os, struct, subprocess, sys, uuid, zlib
import urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(ROOT, "tests", "e2e"))
import staff  # noqa: E402
import tempstaff  # noqa: E402

G = staff.G
LOCAL = os.path.join(HERE, ".local")
SLUG = "bridge-lab"
KEY_LABEL = "bridge-lab seed (private half in bridge-lab/.local)"
DEV_CIDRS = ["127.0.0.1", "172.16.0.0/12", "192.168.0.0/16", "10.0.0.0/8"]

PURPOSES = {  # one plain sentence each (10 to 160 characters, no links): what the dialog says under the label
    "user.basic": "Bridge Lab recognises you across test runs without learning who you are.",
    "user.full_name": "Bridge Lab tests that its server can read your name after you allow it.",
    "user.contact": "Bridge Lab tests that its server can read your phone and email after you allow it.",
    "user.address": "Bridge Lab tests that its server can read your saved place after you allow it.",
    "location.coarse": "Bridge Lab tests that it gets only your city, never your exact position.",
    "location.fine": "Bridge Lab tests that it can read your exact position while it is open.",
    "pay.request": "Bridge Lab tests the payment permission dialog; nothing is ever charged.",
    "notify.inapp": "Bridge Lab sends you test messages inside Adera.",
    "notify.sms": "Bridge Lab sends you test text messages (console only on the dev stack).",
    "notify.email": "Bridge Lab sends you test emails (to the local mail catcher on the dev stack).",
}
GENERIC_PURPOSE = "Bridge Lab tests how the Adera app asks for this permission."

TEMPLATES = [
    {"code": "bridge-lab-inapp", "kind": "IN_APP", "category": "TRANSACTIONAL",
     "params": [{"name": "text", "maxLength": 80, "required": True, "allowLinks": False}],
     "content": {"en": {"title": "Bridge Lab", "body": "{text}"}}},
    {"code": "bridge-lab-direct", "kind": "DIRECT", "category": "TRANSACTIONAL",
     "params": [{"name": "text", "maxLength": 80, "required": True, "allowLinks": False}],
     "content": {"en": {"sms": "Bridge Lab: {text}", "body": "Bridge Lab: {text}", "emailSubject": "Bridge Lab test"}}},
]


def out(msg):
    print(msg, flush=True)


class Admin:
    def __init__(self):
        self.handles = {}

    def token(self, role):
        if role not in self.handles:
            self.handles[role] = tempstaff.open_session(role, keep_audit=True)  # the audit rows stay: they are the history of the registration
        return self.handles[role].token

    def call(self, method, path, body=None, role="ADMIN", expect=(200, 201)):
        st, r = staff.call(method, G + "/admin/api/v1" + path, body, {"Authorization": "Bearer " + self.token(role)})
        if expect and st not in expect:
            raise SystemExit(f"{method} {path} -> {st} {json.dumps(r)[:400]}")
        return r

    def close(self):
        for h in self.handles.values():
            h.close()


def png(w, h, rgb):
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    rows = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def upload_logo(token, app_id):
    boundary = "----lab" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\nContent-Type: image/png\r\n\r\n").encode() \
        + png(256, 256, (40, 90, 200)) + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{G}/admin/api/v1/apps/{app_id}/logo", data=body, method="POST",
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        raise SystemExit(f"logo upload -> {e.code} {e.read()[:300]!r}")


def keypair():
    os.makedirs(LOCAL, mode=0o700, exist_ok=True)
    priv = os.path.join(LOCAL, "partner-dev.pem")
    if not os.path.exists(priv):
        old = os.umask(0o077)
        try:
            subprocess.run(["openssl", "genrsa", "-out", priv, "2048"], check=True, capture_output=True)
        finally:
            os.umask(old)
    os.chmod(priv, 0o600)
    pub = subprocess.run(["openssl", "rsa", "-in", priv, "-pubout"], check=True, capture_output=True, text=True).stdout
    der = base64.b64decode("".join(l for l in pub.splitlines() if not l.startswith("-----")))
    return priv, pub, hashlib.sha256(der).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--entry-url", default="http://localhost:8095/?entry=1")
    ap.add_argument("--alt-origin", default="http://localhost:8096")
    ap.add_argument("--callback-url", default="http://bridge-lab:8095/adera/events")
    ap.add_argument("--gateway-internal", default="http://apisix:9080", help="the gateway as the container reaches it")
    ap.add_argument("--revive", action="store_true", help="bring a KILLED Bridge Lab back to LIVE (temporary SUPER_ADMIN)")
    a = ap.parse_args()
    entry_origin = "/".join(a.entry_url.split("/")[:3])
    origins = [entry_origin] + ([a.alt_origin] if a.alt_origin and a.alt_origin != entry_origin else [])

    tempstaff.require_dev_stack()
    adm = Admin()
    try:
        # 1. the app
        apps = adm.call("GET", "/apps")
        app = next((x for x in apps if x["slug"] == SLUG), None)
        fields = {
            "name": {"en": "Bridge Lab", "am": "ብሪጅ ላብ"},
            "description": {"en": "Test mini app: checks by hand every way the Adera app and a mini app talk (bridge, permissions, launch, callbacks). Not a product."},
            "brandColor": "#285AC8", "categories": ["tools"], "markets": ["ET"],
            "publisherName": "Adera (test tool)", "supportEmail": "bridge-lab@adera.local",
            "entryUrl": a.entry_url, "allowedOrigins": origins,
        }
        if app is None:
            app = adm.call("POST", "/apps", {"slug": SLUG, **fields})
            out(f"created app {SLUG} ({app['id']})")
        else:
            out(f"app {SLUG} exists ({app['id']}, {app['status']})")
        app_id = app["id"]
        detail = adm.call("GET", f"/apps/{app_id}")
        cur = detail["app"]
        # 2. fields, entryUrl, allowedOrigins
        changed = {k: v for k, v in fields.items() if cur.get(k) != v and not (k == "supportEmail" and (cur.get("publisher") or {}).get("supportEmail") == v)
                   and not (k == "publisherName" and (cur.get("publisher") or {}).get("name") == v)}
        if changed:
            adm.call("PATCH", f"/apps/{app_id}", changed)
            out("updated: " + ", ".join(sorted(changed)))
        if not cur.get("logoImageId"):
            upload_logo(adm.token("ADMIN"), app_id)
            out("logo uploaded")
        # 3. every capability, with a purpose
        catalog = adm.call("GET", "/capabilities")
        have = {c["capability"]: c for c in detail["capabilities"]}
        caps = []
        for c in catalog:
            key = c["key"]
            purpose = {"en": PURPOSES.get(key, GENERIC_PURPOSE)}
            h = have.get(key)
            if not h or h["state"] != "GRANTED" or (h.get("purpose") or {}).get("en") != purpose["en"]:
                adm.call("PUT", f"/apps/{app_id}/capabilities/{key}", {"state": "GRANTED", "purpose": purpose})
                out(f"granted {key}")
            caps.append({"key": key, "label": c["label"], "purpose": purpose["en"], "description": c["description"]})
        # 4. DEV environment: caller addresses and callback URL
        env = next((e for e in detail["environments"] if e["environment"] == "DEV"), None)
        norm = lambda cidrs: sorted(c[:-3] if c.endswith("/32") else c for c in cidrs or [])  # the registry stores 127.0.0.1 as 127.0.0.1/32
        if not env or norm(env.get("allowedCidrs")) != norm(DEV_CIDRS) or env.get("callbackUrl") != a.callback_url:
            adm.call("PUT", f"/apps/{app_id}/environments/DEV", {"allowedCidrs": DEV_CIDRS, "callbackUrl": a.callback_url})
            out(f"DEV environment: allowed addresses {DEV_CIDRS}, callback {a.callback_url}")
        # 5. the partner key
        priv, pub, fp = keypair()
        keys = adm.call("GET", f"/apps/{app_id}/keys")
        key = next((k for k in keys if k["environment"] == "DEV" and k["status"] == "ACTIVE" and k["fingerprint"] == fp), None)
        if not key:
            for k in keys:  # an earlier key of ours whose private half is gone
                if k["environment"] == "DEV" and k["status"] == "ACTIVE" and k["label"] == KEY_LABEL:
                    adm.call("DELETE", f"/apps/{app_id}/keys/{k['keyId']}", expect=(200, 204))
            key = adm.call("POST", f"/apps/{app_id}/keys", {"environment": "DEV", "publicKeyPem": pub, "label": KEY_LABEL})
            out(f"registered DEV key {key['keyId']}")
        # 6. templates
        tpls = {}
        existing = {t["code"]: t for t in adm.call("GET", f"/notification/templates?appId={app_id}")}
        for spec in TEMPLATES:
            t = existing.get(spec["code"])
            if t is None:
                t = adm.call("POST", "/notification/templates", {"appId": app_id, **spec})
                out(f"template {spec['code']} created")
            tpls["inapp" if spec["kind"] == "IN_APP" else "direct"] = t["id"]
        # 7. LIVE
        status = adm.call("GET", f"/apps/{app_id}")["app"]["status"]
        if status == "KILLED":
            if not a.revive:
                out("the app is KILLED (test SA02?): run again with --revive to bring it back (needs a temporary SUPER_ADMIN)")
            else:
                adm.call("POST", f"/apps/{app_id}/status", {"status": "LIVE"}, role="SUPER_ADMIN")
                out("revived: LIVE")
        elif status != "LIVE":
            adm.call("POST", f"/apps/{app_id}/status", {"status": "LIVE"})
            out(f"status {status} -> LIVE")
        # 8. the container's config
        cfg = {"appId": app_id, "slug": SLUG, "env": "dev", "keyId": key["keyId"], "privateKeyFile": "partner-dev.pem",
               "gatewayInternal": a.gateway_internal, "entryUrl": a.entry_url, "allowedOrigins": origins, "callbackUrl": a.callback_url,
               "templates": tpls, "capabilities": caps}
        path = os.path.join(LOCAL, "config.json")
        with open(path, "w") as fh:
            json.dump(cfg, fh, indent=2)
        out(f"wrote {path}")
        out(f"ready: app {app_id}, key {key['keyId']}, entryUrl {a.entry_url}, {len(caps)} capabilities, templates {tpls}")
        out("next: cd bridge-lab && docker compose up -d --build   (restart the container after a re-seed: docker compose restart)")
    finally:
        adm.close()


if __name__ == "__main__":
    main()
