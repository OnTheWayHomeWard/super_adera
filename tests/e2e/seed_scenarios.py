"""Sample data for the DEV stack that exercises every scenario of the mini-app flows, through the REAL APIs only
(users through the gateway as real signed-in users, staff actions through the admin API with TEMPORARY staff accounts from
tempstaff.py, partner requests signed with openssl RSA keys), so every row is validated and audited.

  python3 -I tests/e2e/seed_scenarios.py              create / complete the data (safe to run again: nothing is duplicated)
  python3 -I tests/e2e/seed_scenarios.py --remove     delete everything this script created, and nothing else
  python3 -I tests/e2e/seed_scenarios.py --check      only run the read-only verification (masking, role limits, counts)
  options: --no-outage   skip the short Mailpit stop that produces RETRYING / EXPIRED email notifications
           --only a,b    run only some stages (users, apps, ...): for debugging

What it makes (all marked): users with phones +251900000001..015 and display names starting with "Demo "; staff
demo-*@adera.local; apps demo-quota, demo-killed, demo-paused (next to the five from seed_demo.py, which are kept and extended with
keys, templates and allowed addresses); templates with codes seed-*; notifications with external ids seed-*; keys labelled
"seed-scenarios". Ids are kept in tests/e2e/.seed-scenarios-state.json and partner private keys in tests/e2e/.seed-keys/
(both gitignored). DEV only: refuses to run unless notification-service's /dev/messages answers. Nothing real is sent
(console SMS, Mailpit, fake push). Never uses the bootstrap staff account.

--remove deletes through the APIs where one exists (Kratos admin API for identities) and with SQL where none does (the platform
has no delete for users, apps, templates, notifications, reviews, reports, audit rows), like the other e2e scripts do."""
import atexit, base64, collections, hashlib, json, os, re, struct, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request, uuid, zlib
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import staff, tempstaff

G, K, N = staff.G, staff.K, staff.N
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
STATE_PATH = os.path.join(HERE, ".seed-scenarios-state.json")
KEYS_DIR = os.path.join(HERE, ".seed-keys")
KRATOS_ADMIN = "http://127.0.0.1:4434"
USER_SVC = "http://127.0.0.1:8086"
MAILPIT = "http://127.0.0.1:8025"
LABEL = "seed-scenarios"
STAFF_DOMAIN = "adera.local"
passed = failed = 0
notes = []


# ----------------------------------------------------------------------------------------------------------------- basics
def out(msg=""):
    print(msg, flush=True)


def ok(label, cond, extra=""):
    global passed, failed
    passed, failed = (passed + 1, failed) if cond else (passed, failed + 1)
    out(("  PASS " if cond else "  FAIL ") + label + (f"  [{extra}]" if extra != "" else ""))
    return cond


def note(msg):
    notes.append(msg)
    out("  NOTE " + msg)


def env_value(name):
    for line in open(os.path.join(ROOT, ".env")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()


def psql(db, sql):
    return tempstaff._psql(db, sql)


def sqlq(s):
    return "'" + str(s).replace("'", "''") + "'"


def in_list(values):
    return ",".join(sqlq(v) for v in values) or "NULL"


def sh(*args, **kw):
    return subprocess.run(list(args), capture_output=True, text=True, cwd=ROOT, **kw)


_last_call = [0.0]


def call(method, url, body=None, headers=None, retries=4):
    """staff.call with a polite pace (the gateway allows 300 calls a minute per route) and a wait-and-retry on 429."""
    for attempt in range(retries + 1):
        gap = 0.22 - (time.time() - _last_call[0])
        if gap > 0:
            time.sleep(gap)
        _last_call[0] = time.time()
        st, r = staff.call(method, url, body, headers)
        if st == 429 and attempt < retries:
            out("   (429 from the gateway, waiting 30s)")
            time.sleep(30)
            continue
        return st, r


class Gate:
    """The gateway allows 10 submits a minute per IP on Kratos self-service flows (sign-up and login need two each)."""

    def __init__(self, limit=8):
        self.limit, self.stamps = limit, collections.deque()

    def wait(self, n):
        while True:
            now = time.time()
            while self.stamps and now - self.stamps[0] > 61:
                self.stamps.popleft()
            if len(self.stamps) + n <= self.limit:
                break
            time.sleep(max(1.0, 61 - (now - self.stamps[0])))
        for _ in range(n):
            self.stamps.append(time.time())


gate = Gate()


def png(w, h, color):
    rows = b"".join(b"\x00" + bytes(color) * w for _ in range(h))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def multipart(field, filename, content, mime="image/png"):
    b = "----adera" + uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode() + content + f"\r\n--{b}--\r\n".encode()
    return body, f"multipart/form-data; boundary={b}"


def upload(url, headers, field, filename, content):
    body, ctype = multipart(field, filename, content)
    req = urllib.request.Request(url, data=body, method="POST", headers={**headers, "Content-Type": ctype})
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:200]


# ----------------------------------------------------------------------------------------------------------------- state
def load_state():
    try:
        s = json.load(open(STATE_PATH))
    except (OSError, ValueError):
        s = {}
    for k, v in (("users", {}), ("apps", {}), ("staff", {}), ("actors", []), ("done", []), ("keys", {}), ("templates", {}), ("avatars", []), ("aliases", {})):
        s.setdefault(k, v)
    return s


def save_state():
    tmp = STATE_PATH + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump(S, fh, indent=1, sort_keys=True)
    os.replace(tmp, STATE_PATH)


S = load_state()


def done(flag):
    return flag in S["done"]


def mark(flag):
    if flag not in S["done"]:
        S["done"].append(flag)
        save_state()


# ----------------------------------------------------------------------------------------------------------------- specs
COLORS = {"demo-quota": (20, 140, 140), "demo-killed": (110, 110, 110), "demo-paused": (190, 90, 150)}
R = {
    "user.basic": None,
}
NEW_APPS = [
    dict(slug="demo-quota", color=COLORS["demo-quota"], priority=60, status="LIVE",
         name={"en": "Notification Quota Lab", "am": "የመልእክት ገደብ ሙከራ"},
         description={"en": "Demo app that is at or near every notification limit (seed_scenarios).", "am": "ሁሉንም የመልእክት ገደቦች የደረሰ ማሳያ።"},
         brandColor="#148C8C", categories=["tools"], publisherName="Demo Publisher: Quota", supportEmail="support-quota@example.com",
         website="https://example.com/quota", entryUrl="https://example.com/quota", origins=["https://example.com"],
         caps={"user.basic": "GRANTED", "notify.inapp": "GRANTED", "notify.sms": "GRANTED", "notify.email": "GRANTED"},
         limits={"SMS": dict(maxPerUserPerDay=2, maxPerDay=100, maxDistinctUsersPerDay=100, maxTotal=5),
                 "EMAIL": dict(maxPerUserPerDay=None, maxPerDay=3, maxDistinctUsersPerDay=None, maxTotal=None),
                 "IN_APP": dict(maxPerUserPerDay=None, maxPerDay=None, maxDistinctUsersPerDay=2, maxTotal=None)}),
    dict(slug="demo-killed", color=COLORS["demo-killed"], priority=70, status="KILLED",
         name={"en": "Retired Service", "am": "የተቋረጠ አገልግሎት"},
         description={"en": "Demo app that was LIVE, then KILLED by staff (seed_scenarios).", "am": "የተገደለ ማሳያ መተግበሪያ።"},
         brandColor="#6E6E6E", categories=["tools"], publisherName="Demo Publisher: Retired", supportEmail="support-killed@example.com",
         website="https://example.com/retired", entryUrl="https://example.com/retired", origins=["https://example.com"],
         caps={"user.basic": "GRANTED", "notify.sms": "GRANTED", "notify.inapp": "GRANTED"}, limits={}),
    dict(slug="demo-paused", color=COLORS["demo-paused"], priority=80, status="PAUSED",
         name={"en": "Event Tickets", "am": "የዝግጅት ትኬቶች"},
         description={"en": "Demo app with a history: LIVE, PAUSED, LIVE again, PAUSED again (seed_scenarios).", "am": "ታሪክ ያለው ማሳያ።"},
         brandColor="#BE5A96", categories=["events"], publisherName="Demo Publisher: Tickets", supportEmail="support-tickets@example.com",
         website="https://example.com/tickets", entryUrl="https://example.com/tickets", origins=["https://example.com"],
         caps={"user.basic": "GRANTED", "user.full_name": "GRANTED", "notify.inapp": "GRANTED"}, limits={}),
]
REASON_NAME = {"en": "To address you by name on your tickets", "am": "በትኬቶችዎ ላይ በስምዎ ለመጥራት"}

# DEV allowed server addresses: what partner.py uses (the gateway reports the caller address as seen from the compose network)
DEV_CIDRS = ["127.0.0.1", "172.16.0.0/12", "192.168.0.0/16", "10.0.0.0/8"]
KEYED = {  # app slug -> allowed DEV addresses
    "demo-food": DEV_CIDRS, "demo-ride": DEV_CIDRS, "demo-quota": DEV_CIDRS, "demo-killed": DEV_CIDRS,
    "demo-paused": ["203.0.113.77"],  # a documentation address: every request is refused with ip_not_allowed
}

TXT_LONG = "Great app. " * 5
USERS = [
    dict(k="u01", phone="+251900000001", email="demo.abebe@example.com", first="Demo Abebe", last="Kebede", lang="en", gender="male",
         dob="1988-03-14", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", region="Addis Ababa", city="Addis Ababa", latitude=9.0054, longitude=38.7636),
         extra={"occupation": "minibus driver", "vehicle": {"plate": "AA-12345", "seats": 14, "electric": False}, "preferences": {"theme": "dark", "fontSize": 16}, "tags": ["early-adopter", "beta"]},
         avatar=(200, 60, 60), devices=[("ANDROID", "Pixel 7", "14", "1.0.3", "en-ET"), ("IOS", "iPhone 14", "17.5", "1.0.3", "en-US")],
         apps=["demo-food", "demo-ride", "demo-market", "demo-killed", "demo-quota", "demo-paused"],
         mute={"demo-market": dict(muteInApp=True)},
         reviews=[("demo-food", 5, "Fast delivery, the injera was still warm!"), ("demo-ride", 5, "Driver arrived in 3 minutes."), ("demo-paused", 4, "Easy to buy tickets.")],
         reports=[("demo-ride", "BROKEN", "The map freezes when I rotate the phone."), ("demo-market", "SCAM", "A seller asked me to pay outside the app.")]),
    dict(k="u02", phone="+251900000002", email=None, first="Demo ሰላም", last="ተስፋዬ", lang="am", gender="female", dob="1995-04-12", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", region="Amhara", city="Bahir Dar"),
         extra={"occupation": "ነርስ", "languagesSpoken": ["am", "en", "om"], "household": {"size": 4}}, avatar=None, devices=[],
         apps=["demo-food", "demo-ride", "demo-quota", "demo-paused"], mute={},
         reviews=[("demo-food", 5, "በጣም ጥሩ አገልግሎት! ምግቡ ትኩስ ነበር።"), ("demo-ride", 3, None), ("demo-paused", 2, "Was useful once, then it stopped working.")],
         reports=[("demo-food", "SPAM", "Sends me promotions I never asked for.")]),
    dict(k="u03", phone=None, email="demo.hana@example.com", first="Demo Hana", last="Tesfaye", lang="en", gender="other", dob="1992-11-30", tz="Europe/Berlin", market="ET", terms="2026-10",
         loc=None, extra={}, avatar=None, devices=[("IOS", "iPhone 13", "17.2", "1.0.2", "en-DE")],
         apps=["demo-food", "demo-ride", "demo-quota"], mute={"demo-market": dict(muteEmail=True)},
         reviews=[("demo-food", 4, None), ("demo-ride", 1, "Cancelled twice in a row.")],
         reports=[("demo-ride", "OTHER", "The driver asked me to cancel and pay cash.")]),
    dict(k="u04", phone="+251900000004", email=None, first="Demo Dawit", last=None, lang="om", gender="prefer_not_to_say", dob=None, tz="Africa/Addis_Ababa", market="ET", terms=None,
         loc=dict(latitude=8.5414, longitude=39.2707), extra={"note": "coordinates only, no city", "score": 4.5, "verified": True},
         avatar=None, devices=[], apps=["demo-food", "demo-quota"], mute={"demo-food": dict(muteInApp=True)},
         reviews=[("demo-food", 3, "Okay, but the app is slow on weekends.")],
         reports=[("demo-food", "SCAM", "Asked for a prepayment outside the app.")]),
    dict(k="u05", phone="+251900000005", email="demo.mulu@example.com", first="Demo Mulu", last="Alemu", lang="en", gender="female", dob="1979-07-02", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", region="Oromia", city="Adama"),
         extra={"orders": [{"id": 1, "total": 250.5}, {"id": 2, "total": 90}], "nested": {"a": {"b": {"c": [1, 2, {"d": "deep"}]}}}},
         avatar=(30, 140, 70), devices=[("ANDROID", "Galaxy A14", "13", "1.0.1", "en-ET")], apps=["demo-food", "demo-market"],
         mute={"demo-food": dict(muteSms=True)},
         reviews=[("demo-food", 1, "Terrible, my order was wrong."), ("demo-food", 3, "Support fixed my order, upgrading to 3 stars.")],
         reports=[("demo-market", "SPAM", "Dozens of identical listings.")]),
    dict(k="u06", phone="+251900000006", email=None, first="Demo Selam", last="Girma", lang="en", gender="female", dob="2001-01-20", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", city="Hawassa"), extra={"inactive_device_demo": True}, avatar=None,
         devices=[("ANDROID", "Tecno Spark", "12", "0.9.9", "en-ET")], deactivate_device=0, apps=["demo-food"],
         mute={"demo-food": dict(muteEmail=True), "demo-ride": dict(muteSms=True)},
         reviews=[("demo-food", 1, "Never arrived. Do not order.")], reports=[("demo-food", "INAPPROPRIATE", None)]),
    dict(k="u07", phone=None, email="demo.yonas@example.com", first="Demo Yonas", last="Bekele", lang="en", gender="male", dob="1985-09-09", tz="America/New_York", market="US", terms="2026-10",
         loc=dict(countryCode="US", region="Virginia", city="Arlington", latitude=38.88, longitude=-77.1), extra={"diaspora": True, "visits": 3, "since": "2019"},
         avatar=None, devices=[("ANDROID", "Pixel 8", "14", "1.0.3", "en-US")], apps=["demo-food", "demo-ride"], mute={},
         reviews=[("demo-food", 4, "Good variety of restaurants.")], reports=[("demo-food", "BROKEN", "The checkout button does nothing.")]),
    dict(k="u08", phone=None, email="demo.henok@example.com", first="Demo ሄኖክ", last="ገብሩ", lang="am", gender="male", dob="1990-05-05", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", city="Mekelle"), extra={"መስክ": "እሴት"}, avatar=None, devices=[], apps=["demo-food", "demo-quota"], mute={},
         reviews=[("demo-food", 5, None)], reports=[("demo-food", "OTHER", "ዘግይቶ ደረሰ፣ ግን ጥሩ ነበር።")]),
    dict(k="u09", phone=None, email="demo.tigist@example.com", first="Demo Tigist", last="Haile", lang="en", gender="female", dob="1999-12-25", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=None, extra={"emailOnly": True}, avatar=None, devices=[], apps=["demo-food", "demo-quota"], mute={"demo-food": dict(muteEmail=True)},
         reviews=[("demo-food", 2, "ዘግይቶ ደረሰ")], reports=[("demo-food", "SPAM", "Same promo three days in a row.")]),
    dict(k="u10", phone="+251900000010", email=None, first="Demo Kidist", last="Worku", lang="en", gender="female", dob="1994-02-14", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", region="Addis Ababa", city="Addis Ababa"), extra={"temp": "removed later", "keep": 1}, avatar=None,
         devices=[("ANDROID", "Infinix Hot", "12", "1.0.0", "en-ET")], apps=["demo-food", "demo-ride"], mute={},
         reviews=[("demo-food", 4, "Nice"), ("demo-food", None, None)],  # reviewed, then deleted its own review
         reports=[("demo-food", "BROKEN", "Cannot add a second address.")]),
    dict(k="u11", phone="+251900000011", email="demo.suspended@example.com", first="Demo Suspended", last="Sam", lang="en", gender="male", dob="1990-01-01", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", city="Addis Ababa"), extra={"note": "will be suspended"}, avatar=None,
         devices=[("ANDROID", "Pixel 6", "13", "1.0.0", "en-ET")], apps=["demo-food", "demo-ride"], mute={},
         reviews=[("demo-food", 1, "BUY FOLLOWERS CHEAP, DM me on telegram, 100% real")], reports=[]),
    dict(k="u12", phone="+251900000012", email=None, first="Demo Deleted", last="Dina", lang="en", gender="female", dob="1993-03-03", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", city="Addis Ababa"), extra={"note": "will be marked deleted"}, avatar=None,
         devices=[("IOS", "iPhone SE", "16.7", "1.0.0", "en-ET")], apps=["demo-food"], mute={},
         reviews=[("demo-food", 1, "This service is garbage and the owners are idiots.")], reports=[]),
    dict(k="u13", phone="+251900000013", email=None, first="Demo NoApps", last=None, lang="en", gender=None, dob=None, tz=None, market=None, terms=None,
         loc=dict(countryCode="ET", city="Dire Dawa"), clear_location=True, extra=None, avatar=None, devices=[], apps=[], mute={},
         reviews=[], reports=[]),
    dict(k="u14", phone="+251900000014", email=None, first="Demo Blank", last=None, lang=None, gender=None, dob=None, tz=None, market=None, terms=None,
         loc=None, extra=None, avatar=None, devices=[], apps=[], mute={}, reviews=[], reports=[]),
    dict(k="u15", phone="+251900000015", email="demo.full@example.com", first="Demo Fully", last="Filled", lang="en", gender="male", dob="1975-06-15", tz="Africa/Addis_Ababa", market="ET", terms="2026-10",
         loc=dict(countryCode="ET", region="Addis Ababa", city="Addis Ababa", latitude=9.03, longitude=38.74),
         extra={"bio": TXT_LONG * 8, "list": list(range(30)), "mixed": {"s": "text", "n": 1.5, "b": True, "z": None, "arr": ["a", {"k": "v"}]}},
         avatar=(120, 60, 200), devices=[("ANDROID", "Pixel 9", "15", "1.1.0", "en-ET"), ("ANDROID", "Tablet", "14", "1.1.0", "en-ET")],
         apps=["demo-food", "demo-ride", "demo-market"], mute={"demo-food": dict(muteInApp=True, muteSms=True, muteEmail=True), "demo-ride": dict(muteInApp=True)},
         reviews=[], reports=[]),
]
UK = {u["k"]: u for u in USERS}

# review moderation by staff (done as an ADMIN): (app, user, final status after the steps)
HIDE = [("demo-food", "u11", ["HIDDEN"]), ("demo-food", "u12", ["HIDDEN", "VISIBLE"]), ("demo-paused", "u02", ["HIDDEN"])]
# report handling: (app, user, reason, steps)
HANDLE = [("demo-food", "u08", "OTHER", ["REVIEWED"]), ("demo-food", "u09", "SPAM", ["DISMISSED"]), ("demo-food", "u10", "BROKEN", ["REVIEWED", "OPEN"]),
          ("demo-ride", "u03", "OTHER", ["REVIEWED"]), ("demo-market", "u05", "SPAM", ["DISMISSED"]), ("demo-ride", "u01", "BROKEN", ["DISMISSED", "OPEN"])]
EXTRA_REPORT_NOTE = None

STAFF_MEMBERS = [("demo-viewer-1", "VIEWER"), ("demo-viewer-2", "VIEWER"), ("demo-admin-1", "ADMIN"), ("demo-admin-2", "ADMIN")]


# ----------------------------------------------------------------------------------------------------------------- environment
def require_dev():
    tempstaff.require_dev_stack()
    st, _ = call("GET", G + "/kratos/health/alive")
    if st != 200:
        raise SystemExit("the gateway/Kratos does not answer: is the stack up?")


def kratos_find(identifier):
    st, found = staff.call("GET", KRATOS_ADMIN + "/admin/identities?credentials_identifier=" + urllib.parse.quote(identifier))
    return found[0] if st == 200 and isinstance(found, list) and found else None


def host_ip():
    cid = sh("docker", "compose", "ps", "-q", "notification-service").stdout.strip()
    out_ = sh("docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}", cid).stdout.strip()
    return out_ or "172.17.0.1"


# ----------------------------------------------------------------------------------------------------------------- staff sessions
sessions = {}


def staff_token(role):
    """A temporary staff account of this role (removed at exit; its audit rows stay as the history of the seeding).
    `role` may carry a tag ("ADMIN:mod") to get a second, separate account of the same role, so the audit log shows several actors."""
    if role not in sessions:
        gate.wait(5)
        h = tempstaff.open_session(role.split(":")[0], keep_audit=True)
        sessions[role] = h
        if h.identity_id and h.identity_id not in S["actors"]:
            S["actors"].append(h.identity_id)
            save_state()
    return sessions[role].token


def adm(role, method, path, body=None, base="/admin/api/v1"):
    return call(method, G + base + path, body, {"Authorization": "Bearer " + staff_token(role)})


def adm_ok(role, method, path, body=None, expect=(200, 201), what=None):
    st, r = adm(role, method, path, body)
    if st not in expect:
        ok(what or f"{method} {path}", False, f"{st} {json.dumps(r)[:200]}")
    return r if st in expect else None


# ----------------------------------------------------------------------------------------------------------------- users
user_tokens = {}


def otp_for(identifier):
    return staff.otp_from_dev_endpoint(identifier)


def signup_user(u):
    ident = u["phone"] or u["email"]
    for attempt in range(3):
        gate.wait(2)
        staff.call("DELETE", N + "/dev/messages")
        _, f = staff.call("GET", K + "/self-service/registration/api")
        body = {"method": "code"}
        if u["phone"]:
            body["traits.phone_number"] = u["phone"]
        if u["email"]:
            body["traits.email"] = u["email"]
        st, _ = staff.call("POST", f"{K}/self-service/registration?flow={f['id']}", body)
        if st == 429:
            time.sleep(61)
            continue
        code = otp_for(ident)
        st, r = staff.call("POST", f"{K}/self-service/registration?flow={f['id']}", {**body, "code": code})
        if st == 429:
            time.sleep(61)
            continue
        if not r.get("session_token"):
            raise SystemExit(f"sign-up of {u['k']} failed ({st}): {json.dumps(r)[:300]}")
        return r["session_token"], r["identity"]["id"]
    raise SystemExit("rate limited too long")


def login_user(u):
    ident = u["phone"] or u["email"]
    for attempt in range(3):
        gate.wait(2)
        staff.call("DELETE", N + "/dev/messages")
        _, f = staff.call("GET", K + "/self-service/login/api")
        st, _ = staff.call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "code", "identifier": ident})
        if st == 429:
            time.sleep(61)
            continue
        code = otp_for(ident)
        st, r = staff.call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "code", "identifier": ident, "code": code})
        if st == 429:
            time.sleep(61)
            continue
        if not r.get("session_token"):
            raise SystemExit(f"login of {u['k']} failed ({st}): {json.dumps(r)[:300]}")
        return r["session_token"]
    raise SystemExit("rate limited too long")


def ensure_user(u):
    """Returns the user id, signing up when the identity does not exist yet (the session token is kept for this run)."""
    k = u["k"]
    if k in S["users"]:
        return S["users"][k]["id"]
    found = kratos_find(u["phone"] or u["email"])
    if found:
        S["users"][k] = {"id": found["id"], "phone": u["phone"], "email": u["email"]}
        save_state()
        return found["id"]
    tok, uid = signup_user(u)
    user_tokens[k] = tok
    S["users"][k] = {"id": uid, "phone": u["phone"], "email": u["email"]}
    save_state()
    out(f"  signed up {k} {u['phone'] or u['email']}")
    return uid


def uid(k):
    return S["users"][k]["id"]


def utok(k):
    """A session token for a seeded user: the sign-up one from this run, or a fresh login (needs an ACTIVE account)."""
    if k not in user_tokens:
        user_tokens[k] = login_user(UK[k])
    return user_tokens[k]


def uh(k):
    return {"Authorization": "Bearer " + utok(k)}


def ucall(k, method, path, body=None):
    return call(method, G + path, body, uh(k))


def user_status(k):
    st, d = adm("SUPER_ADMIN", "GET", f"/users/{uid(k)}")
    return d.get("status") if st == 200 else None


def mint_alias(user_id, app_id):
    st, r = staff.call("POST", USER_SVC + "/internal/aliases", {"appId": app_id, "userId": user_id}, {"X-Internal-Secret": env_value("INTERNAL_API_SECRET")})
    if st != 200:
        raise SystemExit(f"alias minting failed: {st} {r}")
    return r["alias"]


def alias(k, slug):
    """The pseudonymous id of a user for an app, minted through user-service's internal endpoint (the frontend bridge that does
    this when a user opens an app is not built yet). Kept in the state file: a deleted user can no longer be asked."""
    key = f"{k}:{slug}"
    if key not in S["aliases"]:
        S["aliases"][key] = mint_alias(uid(k), S["apps"][slug]["id"])
        save_state()
    return S["aliases"][key]


def seed_user(u):
    k = u["k"]
    user_id = ensure_user(u)
    steps = [s for s in ("profile", "devices", "aliases", "prefs", "reviews", "reports") if not done(f"user:{k}:{s}")]
    if not steps:
        return
    needs_token = [s for s in steps if s not in ("aliases",)]
    if needs_token:
        status = None
        if k not in user_tokens:
            status = user_status(k)
        if status not in (None, "ACTIVE"):
            note(f"{k} is {status}: user-side steps {needs_token} cannot run (kept as they are)")
            for s in needs_token:
                mark(f"user:{k}:{s}")
            needs_token = []
    out(f"  {k} {u['first']}: {', '.join(steps)}")
    if "profile" in steps and needs_token:
        body = {}
        for key, field in (("first", "firstName"), ("last", "lastName"), ("gender", "gender"), ("dob", "dateOfBirth"), ("lang", "language"), ("tz", "timezone"), ("market", "market"), ("terms", "termsAcceptedVersion")):
            if u.get(key):
                body[field] = u[key]
        if u.get("extra"):
            body["extra"] = u["extra"]
        if body:
            st, r = ucall(k, "PATCH", "/api/v1/me", body)
            ok(f"{k} profile saved", st == 200, st if st != 200 else "")
        if k == "u10":  # extra merge: change one key, remove another, add a new one
            st, r = ucall(k, "PATCH", "/api/v1/me", {"extra": {"temp": None, "added": {"later": True}}})
            ok("u10 extra merge removed 'temp' and kept 'keep'", st == 200 and "temp" not in r["extra"] and r["extra"].get("keep") == 1, st)
        if u.get("loc"):
            st, r = ucall(k, "PUT", "/api/v1/me/location", u["loc"])
            ok(f"{k} location saved", st == 200, st if st != 200 else "")
            if u.get("clear_location"):
                st, r = ucall(k, "DELETE", "/api/v1/me/location")
                ok(f"{k} location cleared again", st in (200, 204), st)
        if u.get("avatar"):
            st, r = upload(G + "/api/v1/me/avatar", uh(k), "file", "me.png", png(640, 480, u["avatar"]))
            ok(f"{k} avatar uploaded", st == 200, st)
            if st == 200 and k not in S["avatars"]:
                S["avatars"].append(k)
                save_state()
        mark(f"user:{k}:profile")
    if "devices" in steps and needs_token:
        for i, (platform, name, osv, appv, locale) in enumerate(u["devices"]):
            tok = "fake-fcm-" + hashlib.sha256(f"{k}:{i}".encode()).hexdigest()[:40]
            st, r = ucall(k, "PUT", "/api/v1/me/devices", {"fcmToken": tok, "platform": platform, "installationId": f"seed-{k}-{i}", "appVersion": appv, "osVersion": osv, "deviceName": name, "locale": locale})
            ok(f"{k} device {name} registered", st == 200, st if st != 200 else "")
            if u.get("deactivate_device") == i:
                st, r = staff.call("POST", USER_SVC + "/internal/devices/deactivate", {"fcmToken": tok}, {"X-Internal-Secret": env_value("INTERNAL_API_SECRET")})
                ok(f"{k} device {name} deactivated (as notification-service does when Firebase reports a dead token)", st == 200 and r.get("deactivated") is True, st)
        mark(f"user:{k}:devices")
    if "aliases" in steps:
        for slug in u["apps"]:
            alias(k, slug)
        mark(f"user:{k}:aliases")
    if "prefs" in steps and needs_token:
        for slug, prefs in u["mute"].items():
            st, r = ucall(k, "PUT", f"/api/v1/me/apps/{S['apps'][slug]['id']}/preferences", prefs)
            ok(f"{k} mute preferences for {slug} {prefs}", st == 200, st if st != 200 else "")
        mark(f"user:{k}:prefs")
    if "reviews" in steps and needs_token:
        for slug, rating, text in u["reviews"]:
            if rating is None:
                st, r = ucall(k, "DELETE", f"/api/v1/apps/{slug}/my-review")
                ok(f"{k} deleted its own review of {slug}", st == 200, st)
            else:
                body = {"rating": rating}
                if text:
                    body["comment"] = text
                st, r = ucall(k, "PUT", f"/api/v1/apps/{slug}/my-review", body)
                ok(f"{k} reviewed {slug}: {rating} stars", st == 200, st if st != 200 else "")
        mark(f"user:{k}:reviews")
    if "reports" in steps and needs_token:
        for slug, reason, details in u["reports"]:
            body = {"reason": reason}
            if details:
                body["details"] = details
            st, r = ucall(k, "POST", f"/api/v1/apps/{slug}/report", body)
            ok(f"{k} reported {slug} ({reason})", st == 200 and r.get("status") == "received", st if st != 200 else "")
        mark(f"user:{k}:reports")


def stage_users():
    out("== users (sign-up through Kratos, profile, devices, aliases, mutes, reviews, reports)")
    note("the user API has no postal address: capability user.address can be granted but a profile holds only name, birth date, gender, language, market, time zone, location (country/region/city/coordinates) and extra JSON")
    for u in USERS:
        seed_user(u)


def stage_user_negatives():
    """User-side validation and rule checks (read-only, nothing is stored)."""
    out("== user-side rules (reviews, reports, profile)")
    if done("negatives"):
        out("  already done")
        return
    k = "u15"
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-ride/my-review", {"rating": 0})
    ok("rating 0 refused", st == 400 and "rating" in r.get("fields", {}), st)
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-ride/my-review", {"rating": 6})
    ok("rating 6 refused", st == 400, st)
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-ride/my-review", {"rating": "5"})
    ok("rating as text refused", st == 400, st)
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-ride/my-review", {"rating": 5, "comment": "x" * 2001})
    ok("comment over 2000 characters refused", st == 400 and "comment" in r.get("fields", {}), st)
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-ride/my-review", {"rating": 5, "stars": 3})
    ok("unknown review field refused", st == 400, st)
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-flights/my-review", {"rating": 4})
    ok("review of a DRAFT app (demo-flights) -> 404 (reviews exist only for LIVE apps)", st == 404, f"{st} {r}")
    st, r = ucall(k, "PUT", "/api/v1/apps/demo-bank/my-review", {"rating": 4})
    ok("review of a PAUSED app (demo-bank) -> 404", st == 404, st)
    st, r = ucall(k, "POST", "/api/v1/apps/demo-flights/report", {"reason": "SPAM"})
    ok("report of a DRAFT app -> 404", st == 404, st)
    st, r = ucall(k, "POST", "/api/v1/apps/demo-ride/report", {"reason": "NOPE"})
    ok("report with an unknown reason refused", st == 400 and "reason" in r.get("fields", {}), st)
    st, r = ucall(k, "POST", "/api/v1/apps/demo-ride/report", {"reason": "SPAM", "details": "d" * 2001})
    ok("report details over 2000 characters refused", st == 400, st)
    st, r = ucall(k, "GET", "/api/v1/apps/demo-ride/my-review")
    ok("my-review of an app never reviewed -> 404", st == 404, st)
    st, r = ucall(k, "PATCH", "/api/v1/me", {"gender": "robot", "language": "xx", "dateOfBirth": "2999-01-01"})
    ok("profile with invalid values -> 400 with a message per field", st == 400 and {"gender", "language", "dateOfBirth"} <= set(r.get("fields", {})), st)
    # a second report of an app that still has an open report from the same user returns the first one, ignoring the new reason
    st, a = ucall("u02", "POST", "/api/v1/apps/demo-food/report", {"reason": "SCAM", "details": "second report, different reason"})
    st2, rows = adm("SUPER_ADMIN", "GET", f"/reports?appId={S['apps']['demo-food']['id']}&size=100")
    mine = [x for x in rows if x["userId"] == uid("u02")]
    ok("a second report from the same user while one is open creates nothing new", st == 200 and len(mine) == 1 and mine[0]["reason"] == "SPAM", f"{len(mine)} rows, reason {mine[0]['reason'] if mine else None}")
    note("a second report with a DIFFERENT reason/details from the same user is silently dropped (answer 200 'received' with the old report id)")
    mark("negatives")


# ----------------------------------------------------------------------------------------------------------------- apps
def logo(app_id, color):
    st, r = upload(f"{G}/admin/api/v1/apps/{app_id}/logo", {"Authorization": "Bearer " + staff_token("ADMIN")}, "file", "logo.png", png(256, 256, color))
    ok("logo uploaded", st == 200, st if st != 200 else "")


def app_id(slug):
    return S["apps"][slug]["id"]


def find_apps():
    st, apps = adm("ADMIN", "GET", "/apps")
    return {a["slug"]: a for a in apps}


config_changed_at = [0.0]


def touched():
    config_changed_at[0] = time.time()


def stage_apps():
    out("== apps (3 new next to the 5 demo apps)")
    existing = find_apps()
    for slug in ("demo-food", "demo-ride", "demo-market", "demo-flights", "demo-bank"):
        if slug not in existing:
            raise SystemExit(f"{slug} does not exist: run tests/e2e/seed_demo.py first")
        S["apps"].setdefault(slug, {"id": existing[slug]["id"], "created": False})
    for spec in NEW_APPS:
        slug = spec["slug"]
        if slug in existing:
            S["apps"].setdefault(slug, {"id": existing[slug]["id"], "created": True})
            continue
        fields = {k: spec[k] for k in ("name", "description", "brandColor", "categories", "publisherName", "supportEmail", "website", "entryUrl", "priority")}
        fields["allowedOrigins"] = spec["origins"]
        fields["markets"] = ["ET"]
        r = adm_ok("ADMIN", "POST", "/apps", {"slug": slug, **fields}, what=f"create {slug}")
        S["apps"][slug] = {"id": r["id"], "created": True}
        save_state()
        out(f"  created {slug}")
        touched()
    for spec in NEW_APPS:
        slug = spec["slug"]
        aid = app_id(slug)
        if done(f"app:{slug}:configured"):
            continue
        if existing.get(slug, {}).get("logoImageId") is None:
            logo(aid, spec["color"])
        for cap, state in spec["caps"].items():
            body = {"state": state}
            if cap == "user.full_name":
                body["reason"] = REASON_NAME
            adm_ok("ADMIN", "PUT", f"/apps/{aid}/capabilities/{cap}", body)
        for channel, limit in spec["limits"].items():
            adm_ok("ADMIN", "PUT", f"/apps/{aid}/notification-limits/{channel}", limit)
        if slug == "demo-paused":
            adm_ok("ADMIN", "PUT", f"/apps/{aid}/payment-settings", {"allowedCurrencies": ["ETB"], "allowHold": True, "minAmountMinor": 1000, "maxAmountMinor": 5000000})
        mark(f"app:{slug}:configured")
        touched()
    # status: LIVE for everyone first (reviews need LIVE apps); PAUSED / KILLED come later, after the users have acted
    now_status = find_apps()
    for spec in NEW_APPS:
        slug = spec["slug"]
        if now_status[slug]["status"] == "DRAFT":
            adm_ok("ADMIN", "POST", f"/apps/{app_id(slug)}/status", {"status": "LIVE"}, what=f"{slug} LIVE")
            touched()
    # allowed DEV addresses for every app that gets a partner key; PROD address stored for demo-ride (not active in DEV)
    for slug, cidrs in KEYED.items():
        if not done(f"app:{slug}:cidrs"):
            adm_ok("ADMIN", "PUT", f"/apps/{app_id(slug)}/environments/DEV", {"allowedCidrs": cidrs})
            if slug == "demo-ride":
                adm_ok("ADMIN", "PUT", f"/apps/{app_id(slug)}/environments/PROD", {"allowedCidrs": ["198.51.100.20"]})
            mark(f"app:{slug}:cidrs")
            touched()
    out("  apps ready: " + ", ".join(f"{s}" for s in S["apps"]))


def stage_app_history():
    """Runs after users and partner traffic: demo-paused goes PAUSED / LIVE / PAUSED again, demo-killed is KILLED."""
    out("== app status history (PAUSED with history, KILLED)")
    apps = find_apps()
    if not done("history:paused"):
        aid = app_id("demo-paused")
        for step in ("PAUSED", "LIVE", "PAUSED"):
            adm_ok("ADMIN", "POST", f"/apps/{aid}/status", {"status": step}, what=f"demo-paused -> {step}")
            if step == "LIVE":
                adm_ok("ADMIN", "PATCH", f"/apps/{aid}", {"description": {"en": "Demo app with a history: LIVE, PAUSED, LIVE again, PAUSED again (seed_scenarios). Back after a fix.", "am": "ታሪክ ያለው ማሳያ።"}})
                adm_ok("ADMIN", "PUT", f"/apps/{aid}/capabilities/notify.sms", {"state": "SUSPENDED"})
        mark("history:paused")
        touched()
    ok("demo-paused is PAUSED after LIVE -> PAUSED -> LIVE -> PAUSED", find_apps()["demo-paused"]["status"] == "PAUSED")
    if not done("history:killed"):
        adm_ok("ADMIN", "POST", f"/apps/{app_id('demo-killed')}/status", {"status": "KILLED"}, what="kill demo-killed")
        mark("history:killed")
        touched()
    ok("demo-killed is KILLED", find_apps()["demo-killed"]["status"] == "KILLED")


# ----------------------------------------------------------------------------------------------------------------- moderation
def stage_moderation():
    out("== moderation (hide / restore reviews, work through reports) as an ADMIN")
    for slug, k, steps in HIDE:
        flag = f"hide:{slug}:{k}"
        if done(flag):
            continue
        st, rows = adm("ADMIN:mod", "GET", f"/apps/{app_id(slug)}/reviews?size=100")
        mine = [r for r in rows if r["userId"] == uid(k)]
        if not mine:
            note(f"no review of {k} on {slug} to moderate")
            continue
        for s in steps:
            st, r = adm("ADMIN:mod", "PATCH", f"/reviews/{mine[0]['id']}", {"status": s})
            ok(f"review of {k} on {slug} -> {s}", st == 200 and r["status"] == s, st)
        mark(flag)
    for slug, k, reason, steps in HANDLE:
        flag = f"handle:{slug}:{k}"
        if done(flag):
            continue
        st, rows = adm("ADMIN:mod", "GET", f"/reports?appId={app_id(slug)}&size=100")
        mine = [r for r in rows if r["userId"] == uid(k) and r["reason"] == reason]
        if not mine:
            note(f"no {reason} report of {k} on {slug} to handle")
            continue
        for s in steps:
            st, r = adm("ADMIN:mod", "PATCH", f"/reports/{mine[0]['id']}", {"status": s})
            ok(f"report {reason} of {k} on {slug} -> {s}", st == 200 and r["status"] == s, st)
        mark(flag)
    st, r = adm("ADMIN:mod", "PATCH", f"/reports/{uuid.uuid4()}", {"status": "REVIEWED"})
    ok("handling a report that does not exist -> 404", st == 404, st)
    st, openrows = adm("ADMIN:mod", "GET", "/reports?status=OPEN&size=1")
    if st == 200 and openrows:
        st, r = adm("ADMIN:mod", "PATCH", f"/reports/{openrows[0]['id']}", {"status": "RESOLVED"})
        ok("report status 'RESOLVED' is refused: the API word for resolved is REVIEWED", st == 400 and "status" in r.get("fields", {}), f"{st} {json.dumps(r)[:120]}")
    st, rows = adm("ADMIN:mod", "GET", "/reports?status=OPEN&size=100")
    st2, total = call("GET", f"{G}/admin/api/v1/reports?status=OPEN&size=1", None, {"Authorization": "Bearer " + staff_token("ADMIN")})
    reopened = [r for r in rows if r["handledBy"] and r["status"] == "OPEN"]
    if reopened:
        note(f"a REOPENED report still carries handledBy/handledAt of its previous handling ({len(reopened)} such OPEN rows; e.g. handledAt {reopened[0]['handledAt']})")


# ----------------------------------------------------------------------------------------------------------------- partner keys, templates
def key_paths(slug, env="dev"):
    return os.path.join(KEYS_DIR, f"{slug}.{env}.pem"), os.path.join(KEYS_DIR, f"{slug}.{env}.pub")


def ensure_keypair(slug, env="dev"):
    os.makedirs(KEYS_DIR, mode=0o700, exist_ok=True)
    priv, pub = key_paths(slug, env)
    if not os.path.exists(priv):
        old = os.umask(0o077)
        try:
            subprocess.run(["openssl", "genrsa", "-out", priv, "2048"], check=True, capture_output=True)
        finally:
            os.umask(old)
        os.chmod(priv, 0o600)
    subprocess.run(["openssl", "rsa", "-in", priv, "-pubout", "-out", pub], check=True, capture_output=True)
    pem = open(pub).read()
    der = base64.b64decode("".join(l for l in pem.splitlines() if not l.startswith("-----")))
    return pem, hashlib.sha256(der).hexdigest()


def ensure_key(slug, env="DEV"):
    """Registers the PUBLIC half of a key kept in tests/e2e/.seed-keys/ (the private half never leaves this machine)."""
    tag = env.lower()
    pem, fp = ensure_keypair(slug, tag)
    aid = app_id(slug)
    st, keys = adm("ADMIN", "GET", f"/apps/{aid}/keys")
    for kv in keys:
        if kv["environment"] == env and kv["status"] == "ACTIVE" and kv["label"] == LABEL and kv["fingerprint"] == fp:
            S["keys"][f"{slug}:{tag}"] = kv["keyId"]
            save_state()
            return kv["keyId"]
    for kv in keys:  # a key of ours whose private half is gone: retire it
        if kv["environment"] == env and kv["status"] == "ACTIVE" and kv["label"] == LABEL:
            adm("ADMIN", "DELETE", f"/apps/{aid}/keys/{kv['keyId']}")
    r = adm_ok("ADMIN", "POST", f"/apps/{aid}/keys", {"environment": env, "publicKeyPem": pem, "label": LABEL}, what=f"register {env} key for {slug}")
    S["keys"][f"{slug}:{tag}"] = r["keyId"]
    save_state()
    out(f"  registered {env} key {r['keyId']} for {slug}")
    touched()
    return r["keyId"]


def stage_keys():
    out("== partner keys (public halves registered; private halves in tests/e2e/.seed-keys/, gitignored)")
    for slug in KEYED:
        ensure_key(slug, "DEV")
    ensure_key("demo-ride", "PROD")
    # a revoked key for the history: register, then revoke
    if not done("key:revoked"):
        pem, _ = ensure_keypair("demo-food", "old")
        r = adm_ok("ADMIN", "POST", f"/apps/{app_id('demo-food')}/keys", {"environment": "DEV", "publicKeyPem": pem, "label": LABEL + " (rotated out)"})
        if r:
            S["keys"]["demo-food:old"] = r["keyId"]
            adm_ok("ADMIN", "DELETE", f"/apps/{app_id('demo-food')}/keys/{r['keyId']}")
        mark("key:revoked")
    if not done("key:expiring"):
        pem, _ = ensure_keypair("demo-ride", "exp")
        r = adm_ok("ADMIN", "POST", f"/apps/{app_id('demo-ride')}/keys", {"environment": "DEV", "publicKeyPem": pem, "label": LABEL + " (expires)", "expiresAt": "2027-06-30T00:00:00Z"})
        if r:
            S["keys"]["demo-ride:exp"] = r["keyId"]
        mark("key:expiring")


def T(code, kind, category, params, content, **kw):
    return dict(code=code, kind=kind, category=category, params=params, content=content, **kw)


def P(name, maxLength=100, required=True, allowLinks=False):
    return {"name": name, "maxLength": maxLength, "required": required, "allowLinks": allowLinks}


FUTURE_EXPIRY = "2027-12-31T00:00:00Z"
TEMPLATES = {
    "demo-food": [
        T("seed-order-confirmed", "DIRECT", "TRANSACTIONAL", [P("name", 20), P("orderNo", 12)],
          {"en": {"sms": "Hi {name}, your order {orderNo} is confirmed.", "body": "Hi {name}, your order {orderNo} is confirmed and the kitchen has started.", "emailSubject": "Order {orderNo} confirmed"},
           "am": {"sms": "ሰላም {name}፣ ትዕዛዝ {orderNo} ተረጋግጧል።", "body": "ሰላም {name}፣ ትዕዛዝ {orderNo} ተረጋግጧል።", "emailSubject": "ትዕዛዝ {orderNo} ተረጋግጧል"}}),
        T("seed-order-update", "IN_APP", "TRANSACTIONAL", [P("name", 30), P("eta", 10)],
          {"en": {"title": "Your order is on the way", "body": "Hi {name}, it arrives in about {eta}."}, "am": {"title": "ትዕዛዝዎ በመንገድ ላይ ነው", "body": "ሰላም {name}፣ በ{eta} ውስጥ ይደርሳል።"}}),
        T("seed-track-link", "DIRECT", "TRANSACTIONAL", [P("name", 20), P("trackingUrl", 120, allowLinks=True), P("note", 5, required=False)],
          {"en": {"sms": "Hi {name}, track your order: {trackingUrl}", "body": "Hi {name}, track your order: {trackingUrl}", "emailSubject": "Track your order"}}),
        T("seed-weekend-promo", "DIRECT", "PROMOTIONAL", [P("name", 20)], {"en": {"sms": "{name}, 20% off this weekend!", "body": "{name}, 20% off this weekend!"}}, active=False),
        T("seed-holiday-greeting", "IN_APP", "REMINDER", [P("name", 20)], {"en": {"title": "Happy holidays", "body": "Warm wishes, {name}."}},
          expiresAt=FUTURE_EXPIRY, expire_later=True),
        T("seed-new-year-offer", "IN_APP", "PROMOTIONAL", [P("name", 20)], {"en": {"title": "New year offer", "body": "A little something for you, {name}."}},
          validFrom="2030-01-01T00:00:00Z"),
    ],
    "demo-ride": [
        T("seed-ride-arriving", "DIRECT", "TRANSACTIONAL", [P("driver", 25), P("plate", 10)],
          {"en": {"sms": "{driver} ({plate}) is arriving now.", "body": "{driver} ({plate}) is arriving now.", "emailSubject": "Your ride is arriving"},
           "am": {"sms": "{driver} ({plate}) አሁን እየደረሰ ነው።", "body": "{driver} ({plate}) አሁን እየደረሰ ነው።"}}),
        T("seed-ride-receipt", "IN_APP", "TRANSACTIONAL", [P("fare", 10)], {"en": {"title": "Trip receipt", "body": "Your fare was {fare} ETB."}, "am": {"title": "የጉዞ ደረሰኝ", "body": "ክፍያዎ {fare} ብር ነበር።"}}),
        T("seed-ride-old-offer", "DIRECT", "PROMOTIONAL", [P("name", 20)], {"en": {"sms": "{name}, ride credit expires soon.", "body": "{name}, ride credit expires soon."}},
          expiresAt=FUTURE_EXPIRY, expire_later=True),
    ],
    "demo-quota": [
        T("seed-quota-ping", "DIRECT", "TRANSACTIONAL", [P("code", 10)], {"en": {"sms": "Your code is {code}.", "body": "Your code is {code}.", "emailSubject": "Your code"}}),
        T("seed-quota-inapp", "IN_APP", "TRANSACTIONAL", [P("code", 10)], {"en": {"title": "Ping", "body": "Ping {code}."}}),
    ],
    "demo-killed": [
        T("seed-killed-ping", "DIRECT", "TRANSACTIONAL", [P("name", 20)], {"en": {"sms": "Hello {name}.", "body": "Hello {name}."}}),
    ],
    "demo-paused": [
        T("seed-ticket-reminder", "IN_APP", "REMINDER", [P("event", 40)], {"en": {"title": "Event reminder", "body": "{event} starts tomorrow."}}),
    ],
}
TPL = {}


def stage_templates():
    out("== templates (created by staff; partners cannot create them)")
    for slug, specs in TEMPLATES.items():
        aid = app_id(slug)
        st, have = adm("ADMIN:content", "GET", f"/notification/templates?appId={aid}")
        by_code = {t["code"]: t for t in have}
        for spec in specs:
            body = {k: v for k, v in spec.items() if k != "expire_later"}
            body["appId"] = aid
            t = by_code.get(spec["code"])
            if t is None:
                st, t = adm("ADMIN:content", "POST", "/notification/templates", body)
                if st != 200:
                    ok(f"template {spec['code']}", False, f"{st} {json.dumps(t)[:200]}")
                    continue
                out(f"  created {slug}/{spec['code']}")
            if spec.get("expire_later") and t.get("expiresAt", "") and not done(f"tpl:{spec['code']}:expired"):
                # a template cannot be CREATED already expired; it can be edited into the past
                st, t = adm("ADMIN:content", "PATCH", f"/notification/templates/{t['id']}", {"expiresAt": "2026-01-01T00:00:00Z"})
                ok(f"{spec['code']} edited to be expired", st == 200 and t["unusableReason"] == "template_expired", f"{st} {t.get('unusableReason') if isinstance(t, dict) else t}")
                mark(f"tpl:{spec['code']}:expired")
            TPL[(slug, spec["code"])] = t["id"]
            S["templates"][f"{slug}/{spec['code']}"] = t["id"]
    save_state()
    # one edit that bumps a version (history), and a duplicate-code refusal
    if not done("tpl:edit"):
        tid = TPL[("demo-food", "seed-order-update")]
        st, t = adm("ADMIN:content", "PATCH", f"/notification/templates/{tid}", {"content": {"en": {"title": "Your order is on the way", "body": "Hi {name}, it arrives in about {eta}."}, "am": {"title": "ትዕዛዝዎ በመንገድ ላይ ነው", "body": "ሰላም {name}፣ በ{eta} ውስጥ ይደርሳል።"}, "om": {"title": "Ajajni kee karaa irra jira", "body": "Akkam {name}, {eta} keessatti ni ga'a."}}})
        ok("template edited (adds Afaan Oromo, version bumps)", st == 200 and t["version"] >= 2, st)
        mark("tpl:edit")
    st, r = adm("ADMIN:content", "POST", "/notification/templates", {"appId": app_id("demo-food"), "code": "seed-order-update", "kind": "IN_APP", "category": "TRANSACTIONAL", "params": [], "content": {"en": {"title": "x", "body": "y"}}})
    ok("a template code that exists -> 409 template_code_taken", st == 409, st)
    st, r = adm("ADMIN:content", "POST", "/notification/templates", {"appId": app_id("demo-food"), "code": "seed-bad", "kind": "DIRECT", "category": "TRANSACTIONAL", "params": [], "content": {"en": {"body": "Hi {unknown}"}}})
    ok("a placeholder that is not a declared param -> 400", st == 400, st)
    st, r = adm("ADMIN:content", "POST", "/notification/templates", {"appId": app_id("demo-food"), "code": "seed-past", "kind": "DIRECT", "category": "TRANSACTIONAL", "params": [], "content": {"en": {"body": "hi"}}, "expiresAt": "2020-01-01T00:00:00Z"})
    ok("creating a template that is already expired -> 400", st == 400, st)
    touched()


# ----------------------------------------------------------------------------------------------------------------- partner traffic
received = []


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        received.append((self.path, dict(self.headers), body))
        self.send_response(500 if self.path == "/hook-500" else 200)
        self.end_headers()

    def log_message(self, *a):
        pass


hook_server = [None]


def hook_base():
    if hook_server[0] is None:
        srv = HTTPServer(("0.0.0.0", 0), Hook)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        hook_server[0] = srv
    return f"http://{host_ip()}:{hook_server[0].server_address[1]}"


def rs256(slug_tag, canonical):
    priv = key_paths(*slug_tag.split(":"))[0]
    return base64.b64encode(subprocess.run(["openssl", "dgst", "-sha256", "-sign", priv], input=canonical.encode(), capture_output=True, check=True).stdout).decode()


def pcall(slug, method, path, body=None, env="dev", ts=None, tamper=False, key_id=None):
    data = json.dumps(body).encode() if body is not None else b""
    ts = str(ts or int(time.time()))
    canonical = f"ADERA-SIG-1\n{ts}\n{method}\n{path}\n{base64.b64encode(hashlib.sha256(data).digest()).decode()}"
    sig = rs256(f"{slug}:{env}", canonical)
    if tamper:
        data = data.replace(b"Abebe", b"Hacker")
    headers = {"X-Adera-Key-Id": key_id or S["keys"][f"{slug}:{env}"], "X-Adera-Timestamp": ts, "X-Adera-Signature": sig, "Content-Type": "application/json"}
    for attempt in range(3):
        try:
            r = urllib.request.urlopen(urllib.request.Request(G + path, data=data or None, method=method, headers=headers), timeout=30)
            code, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            code, raw = e.code, e.read()
        if code == 429 and json.loads(raw or b"{}").get("error") != "quota_exceeded" and attempt < 2:
            time.sleep(20)
            continue
        break
    try:
        return code, json.loads(raw or b"{}")
    except ValueError:
        return code, {"raw": raw[:100].decode(errors="replace")}


FINAL = {"SENT", "FAILED", "UNREACHABLE", "SUPPRESSED", "EXPIRED"}


def settle(slug, ext, want=None, timeout=25):
    """Polls the partner's own status endpoint until the notification reaches a final state (or the wanted one)."""
    end = time.time() + timeout
    r = {}
    while time.time() < end:
        code, r = pcall(slug, "GET", f"/v1/notifications/{ext}")
        if code == 200 and (r.get("status") == want if want else r.get("status") in FINAL):
            return r
        time.sleep(1)
    return r


def send(slug, ext, who, tpl, params=None, expect=(202, "QUEUED"), final=None, flag=True, **extra):
    """One partner request. `who` is a user key (u01) or a raw alias. expect = (http status, notification status or error code).
    final = the status it should settle to. A request that is refused leaves no record, so it runs once (flag)."""
    f = f"send:{slug}:{ext}"
    if flag and done(f):
        return None
    to = alias(who, slug) if who in UK else who
    body = {"externalId": ext, "userId": to, "templateId": TPL.get((slug, tpl), tpl), "params": params or {}}
    body.update(extra)
    code, r = pcall(slug, "POST", "/v1/notifications", body)
    want_code, want = expect[0], expect[1]
    got = r.get("status") or r.get("error")
    good = code == want_code and got == want and (len(expect) < 3 or r.get("limit") == expect[2])
    extra_txt = r.get("limit") or r.get("capability") or (list(r.get("fields", {}).items())[:1] if r.get("fields") else "")
    ok(f"{slug} {ext}: {code} {got} {extra_txt}".rstrip(), good, "" if good else f"wanted {want_code} {want}: {json.dumps(r)[:160]}")
    if good and final:
        res = settle(slug, ext)
        ok(f"{slug} {ext} settles to {final}", res.get("status") == final, f"{res.get('status')} {res.get('reason', '')}")
    if flag and good:
        mark(f)
    return r


def stage_partner():
    out("== partner traffic (signed requests through the gateway)")
    left = 17 - (time.time() - config_changed_at[0])
    if config_changed_at[0] and left > 0:
        out(f"   waiting {int(left)}s for notification-service to refresh its 15s app-config cache")
        time.sleep(left)
    hb = hook_base()
    F, RD, Q, KL, PA = "demo-food", "demo-ride", "demo-quota", "demo-killed", "demo-paused"
    code, r = pcall(F, "GET", "/v1/templates")
    ok("partner lists its approved templates (GET /v1/templates)", code == 200 and any(t["code"] == "seed-order-confirmed" for t in r) if isinstance(r, list) else False, code)
    code, r = pcall(F, "GET", "/v1/notifications/seed-unknown-id")
    ok("status of an unknown externalId -> 404 not_found", code == 404, code)

    out(" -- food: SMS, EMAIL fallback, IN_APP + inbox + fake push, muted, callbacks")
    send(F, "seed-food-sms-1", "u01", "seed-order-confirmed", {"name": "Abebe", "orderNo": "A-1001"}, final="SENT", callbackUrl=hb + "/hook")
    send(F, "seed-food-sms-2", "u02", "seed-order-confirmed", {"name": "ሰላም", "orderNo": "A-1002"}, final="SENT")
    send(F, "seed-food-email-1", "u03", "seed-order-confirmed", {"name": "Hana", "orderNo": "A-1003"}, final="SENT")
    send(F, "seed-food-link-1", "u01", "seed-track-link", {"name": "Abebe", "trackingUrl": "https://track.example.com/A-1001", "note": "ok"}, final="SENT")
    send(F, "seed-food-inapp-1", "u01", "seed-order-update", {"name": "Abebe", "eta": "10 min"}, final="SENT", data={"orderId": "A-1001", "screen": "tracking"}, callbackUrl=hb + "/hook")
    send(F, "seed-food-inapp-2", "u01", "seed-order-update", {"name": "Abebe", "eta": "5 min"}, final="SENT")
    send(F, "seed-food-inapp-3", "u01", "seed-order-update", {"name": "Abebe", "eta": "2 min"}, final="SENT")
    send(F, "seed-food-inapp-4", "u02", "seed-order-update", {"name": "ሰላም", "eta": "ከ10 ደቂቃ"}, final="SENT")  # no devices: inbox only
    send(F, "seed-food-inapp-5", "u02", "seed-order-update", {"name": "ሰላም", "eta": "5 ደቂቃ"}, final="SENT")
    send(F, "seed-food-inapp-6", "u03", "seed-order-update", {"name": "Hana", "eta": "8 min"}, final="SENT")
    send(F, "seed-food-inapp-7", "u05", "seed-order-update", {"name": "Mulu", "eta": "3 min"}, final="SENT")
    send(F, "seed-food-inapp-8", "u06", "seed-order-update", {"name": "Selam", "eta": "4 min"}, final="SENT")  # its only device was deactivated
    send(F, "seed-food-sms-muted", "u05", "seed-order-confirmed", {"name": "Mulu", "orderNo": "A-1005"}, expect=(200, "SUPPRESSED"), callbackUrl=hb + "/hook")
    send(F, "seed-food-inapp-muted", "u04", "seed-order-update", {"name": "Dawit", "eta": "6 min"}, expect=(200, "SUPPRESSED"))
    send(F, "seed-food-email-muted", "u09", "seed-order-confirmed", {"name": "Tigist", "orderNo": "A-1009"}, expect=(200, "SUPPRESSED"))
    send(F, "seed-food-inapp-allmuted", "u15", "seed-order-update", {"name": "Fully", "eta": "1 min"}, expect=(200, "SUPPRESSED"))
    send(F, "seed-food-inapp-u15-sms", "u15", "seed-order-confirmed", {"name": "Fully", "orderNo": "A-1015"}, expect=(200, "SUPPRESSED"))

    out(" -- synthetic users (DEV only)")
    send(F, "seed-food-synth-ok", "u_p_test_ok", "seed-order-confirmed", {"name": "Test", "orderNo": "T-1"}, final="SENT")
    send(F, "seed-food-synth-muted", "u_p_test_muted", "seed-order-confirmed", {"name": "Test", "orderNo": "T-2"}, expect=(200, "SUPPRESSED"))
    send(F, "seed-food-synth-unreachable", "u_p_test_unreachable", "seed-order-confirmed", {"name": "Test", "orderNo": "T-3"}, expect=(422, "user_unreachable"))
    send(F, "seed-food-unknown-user", "u_p_0123456789abcdef0123456789abcdef", "seed-order-confirmed", {"name": "Nobody", "orderNo": "X-1"}, expect=(422, "user_unreachable"))

    out(" -- replay and external id reuse")
    first = {"externalId": "seed-food-sms-2", "userId": alias("u02", F), "templateId": TPL[(F, "seed-order-confirmed")], "params": {"name": "ሰላም", "orderNo": "A-1002"}}
    code, r = pcall(F, "POST", "/v1/notifications", first)
    ok("same request again -> 200 replayed:true (current status returned)", code == 200 and r.get("replayed") is True, f"{code} {r.get('status')}")
    code, r = pcall(F, "POST", "/v1/notifications", {**first, "params": {"name": "Other", "orderNo": "A-1001"}})
    ok("same externalId, different content -> 409 external_id_reused", code == 409 and r.get("error") == "external_id_reused", code)

    out(" -- refusals: validation, template state, capability, per-user quota")
    send(F, "seed-food-bad-1", "u01", "seed-order-confirmed", {"name": "Abebe"}, expect=(422, "param_invalid"))  # orderNo missing
    send(F, "seed-food-bad-2", "u01", "seed-order-confirmed", {"name": "N" * 21, "orderNo": "A-1"}, expect=(422, "param_invalid"))  # longer than maxLength 20
    send(F, "seed-food-bad-3", "u01", "seed-order-confirmed", {"name": "see http://evil.example", "orderNo": "A-1"}, expect=(422, "param_invalid"))
    send(F, "seed-food-bad-4", "u01", "seed-order-confirmed", {"name": "Abebe", "orderNo": "A-1", "extra": "x"}, expect=(422, "param_invalid"))
    send(F, "seed-food-bad-5", "u01", "seed-track-link", {"name": "Abebe", "trackingUrl": "https://x.example/1", "note": "too long note"}, expect=(422, "param_invalid"))
    send(F, "seed-food-bad-6", "u01", "seed-order-update", {"name": "Abebe\u0007", "eta": "1"}, expect=(422, "param_invalid"))  # control character
    send(F, "seed-food-tpl-inactive", "u01", "seed-weekend-promo", {"name": "Abebe"}, expect=(422, "template_inactive"))
    send(F, "seed-food-tpl-expired", "u01", "seed-holiday-greeting", {"name": "Abebe"}, expect=(422, "template_expired"))
    send(F, "seed-food-tpl-future", "u01", "seed-new-year-offer", {"name": "Abebe"}, expect=(422, "template_not_yet_valid"))
    send(F, "seed-food-tpl-other-app", "u01", RD and TPL[(RD, "seed-ride-receipt")], {"fare": "10"}, expect=(404, "template_not_found"))
    for ext, body, want in (
            ("not an id!", {"externalId": "not an id!", "userId": alias("u01", F), "templateId": TPL[(F, "seed-order-update")]}, (400, "validation_failed")),
            ("seed-food-v2", {"externalId": "seed-food-v2", "userId": "bob", "templateId": TPL[(F, "seed-order-update")]}, (400, "validation_failed")),
            ("seed-food-v3", {"externalId": "seed-food-v3", "userId": alias("u01", F), "templateId": "nope"}, (400, "validation_failed")),
            ("seed-food-v4", {"externalId": "seed-food-v4", "userId": alias("u01", F), "templateId": TPL[(F, "seed-order-update")], "ttlSeconds": 5}, (400, "validation_failed")),
            ("seed-food-v5", {"externalId": "seed-food-v5", "userId": alias("u01", F), "templateId": TPL[(F, "seed-order-update")], "unknown": 1}, (400, "validation_failed"))):
        code, r = pcall(F, "POST", "/v1/notifications", body)
        ok(f"request validation ({ext}) -> {code} {r.get('error')} {list(r.get('fields', {}))}", (code, r.get("error")) == want, f"{code} {r}")
    code, r = pcall(F, "POST", "/v1/notifications", ["not", "an", "object"])
    ok("body that is not a JSON object -> 400", code == 400, f"{code} {r}")
    # per-user quota on SMS (food: 3 per user per day): u06 gets three, the fourth is refused
    for i in (1, 2, 3):
        send(F, f"seed-food-quota-user-{i}", "u06", "seed-order-confirmed", {"name": "Selam", "orderNo": f"Q-{i}"}, final="SENT")
    send(F, "seed-food-quota-user-4", "u06", "seed-order-confirmed", {"name": "Selam", "orderNo": "Q-4"}, expect=(429, "quota_exceeded", "max_per_user_per_day"))

    out(" -- callbacks: delivered, retrying, failed")
    send(F, "seed-food-cb-500", "u02", "seed-order-confirmed", {"name": "ሰላም", "orderNo": "C-1"}, final="SENT", callbackUrl=hb + "/hook-500")
    send(F, "seed-food-cb-refused", "u07", "seed-order-update", {"name": "Yonas", "eta": "9 min"}, final="SENT", callbackUrl=f"http://{host_ip()}:9/hook")
    send(F, "seed-food-cb-invalid", "u07", "seed-order-update", {"name": "Yonas", "eta": "7 min"}, final="SENT", callbackUrl=f"http://user@{host_ip()}:9/hook")
    note("the partner API accepts a callbackUrl with credentials (http://user@host/): the callback is created and then fails at once with last_error invalid_url, a refusal that could have been 400 at the door")

    out(" -- ride: SMS, IN_APP, capability, wrong environment, bad signature")
    send(RD, "seed-ride-sms-1", "u01", "seed-ride-arriving", {"driver": "Kebede", "plate": "AA-1234"}, final="SENT")
    send(RD, "seed-ride-sms-2", "u02", "seed-ride-arriving", {"driver": "Tesfaye", "plate": "AA-9999"}, final="SENT")
    send(RD, "seed-ride-inapp-1", "u01", "seed-ride-receipt", {"fare": "180"}, final="SENT")
    send(RD, "seed-ride-inapp-2", "u07", "seed-ride-receipt", {"fare": "95"}, final="SENT")
    send(RD, "seed-ride-sms-muted", "u06", "seed-ride-arriving", {"driver": "Girma", "plate": "AA-777"}, expect=(200, "SUPPRESSED"))
    send(RD, "seed-ride-email-denied", "u03", "seed-ride-arriving", {"driver": "Girma", "plate": "AA-777"}, expect=(403, "capability_not_granted"))  # email-only user, notify.email is SUSPENDED
    send(RD, "seed-ride-tpl-expired", "u01", "seed-ride-old-offer", {"name": "Abebe"}, expect=(422, "template_expired"))
    code, r = pcall(RD, "POST", "/v1/notifications", {"externalId": "seed-ride-prod", "userId": alias("u01", RD), "templateId": TPL[(RD, "seed-ride-receipt")], "params": {"fare": "1"}}, env="prod")
    ok("a PROD key on this DEV deployment -> 403 wrong_environment", code == 403 and r.get("error") == "wrong_environment", f"{code} {r}")
    msg = {"externalId": "seed-ride-sig", "userId": alias("u01", RD), "templateId": TPL[(RD, "seed-ride-receipt")], "params": {"fare": "Abebe"}}
    code, r = pcall(RD, "POST", "/v1/notifications", msg, tamper=True)
    ok("body changed after signing -> 401 invalid_signature", code == 401 and r.get("error") == "invalid_signature", f"{code} {r}")
    code, r = pcall(RD, "POST", "/v1/notifications", msg, ts=int(time.time()) - 3600)
    ok("old timestamp -> 401 stale_timestamp", code == 401 and r.get("error") == "stale_timestamp", f"{code} {r}")
    code, r = pcall(RD, "POST", "/v1/notifications", msg, key_id="ak_dev_unknown")
    ok("unknown key id -> 401 unknown_key", code == 401 and r.get("error") == "unknown_key", f"{code} {r}")
    code, r = pcall(RD, "POST", "/v1/notifications", msg, key_id=S["keys"][f"{F}:dev"])
    ok("another app's key id with our signature -> 401 invalid_signature", code == 401, f"{code} {r}")

    out(" -- quota lab: every limit name (demo-quota)")
    for i, who in enumerate(("u01", "u01")):
        send(Q, f"seed-quota-sms-a{i}", who, "seed-quota-ping", {"code": f"10{i}"}, final="SENT")
    send(Q, "seed-quota-sms-peruser", "u01", "seed-quota-ping", {"code": "103"}, expect=(429, "quota_exceeded", "max_per_user_per_day"))
    for i in range(2):
        send(Q, f"seed-quota-sms-b{i}", "u02", "seed-quota-ping", {"code": f"20{i}"}, final="SENT")
    send(Q, "seed-quota-sms-c0", "u04", "seed-quota-ping", {"code": "301"}, final="SENT")  # 5th SMS: the lifetime total of 5 is used up
    send(Q, "seed-quota-sms-total", "u04", "seed-quota-ping", {"code": "302"}, expect=(429, "quota_exceeded", "max_total"))
    send(Q, "seed-quota-email-1", "u03", "seed-quota-ping", {"code": "401"}, final="SENT")
    send(Q, "seed-quota-email-2", "u03", "seed-quota-ping", {"code": "402"}, final="SENT")
    send(Q, "seed-quota-email-3", "u08", "seed-quota-ping", {"code": "403"}, final="SENT")
    send(Q, "seed-quota-email-day", "u09", "seed-quota-ping", {"code": "404"}, expect=(429, "quota_exceeded", "max_per_day"))
    send(Q, "seed-quota-inapp-1", "u01", "seed-quota-inapp", {"code": "501"}, final="SENT")
    send(Q, "seed-quota-inapp-2", "u02", "seed-quota-inapp", {"code": "502"}, final="SENT")
    send(Q, "seed-quota-inapp-3", "u01", "seed-quota-inapp", {"code": "503"}, final="SENT")  # same user again: allowed
    send(Q, "seed-quota-inapp-distinct", "u03", "seed-quota-inapp", {"code": "504"}, expect=(429, "quota_exceeded", "max_distinct_users_per_day"))
    out(" -- demo-paused (refused by address) and demo-killed")
    send(PA, "seed-paused-ip", "u01", "seed-ticket-reminder", {"event": "Jazz Night"}, expect=(403, "ip_not_allowed"))
    send(KL, "seed-killed-sent-before", "u01", "seed-killed-ping", {"name": "Abebe"}, final="SENT")


def stage_partner_state_changes():
    """Needs the users to still be ACTIVE: queue a message and suspend / delete at once, then the refusals."""
    out("== account states seen by partners (suspended, deleted)")
    F = "demo-food"
    for k, new_status, reason in (("u11", "SUSPENDED", "Review spam: promoted followers in a review"), ("u12", "DELETED", "Abusive review and user asked to be removed")):
        ext = f"seed-food-race-{k}"
        if not done(f"send:{F}:{ext}"):
            before = user_status(k)
            if before == "ACTIVE":
                send(F, ext, k, "seed-order-confirmed", {"name": "Sam", "orderNo": "R-1"}, flag=False)
                adm_ok("SUPER_ADMIN", "POST", f"/users/{uid(k)}/status", {"status": new_status, "reason": reason}, what=f"{k} -> {new_status}")
                res = settle(F, ext)
                ok(f"{ext}: queued, then {k} {new_status} before delivery -> notification ends {res.get('status')} ({res.get('reason', '')})", res.get("status") in ("UNREACHABLE", "SENT"), res)
                if res.get("status") != "UNREACHABLE":
                    note(f"the delivery worker (1s tick) won the race for {k}: the message was SENT before the status change; UNREACHABLE needs a status change inside that second")
                mark(f"send:{F}:{ext}")
            else:
                note(f"{k} is {before}, not touched")
                mark(f"send:{F}:{ext}")
    st_sus = user_status("u11")
    ok("u11 is SUSPENDED", st_sus == "SUSPENDED", st_sus)
    ok("u12 is DELETED", user_status("u12") == "DELETED")
    send(F, "seed-food-suspended", "u11", "seed-order-confirmed", {"name": "Sam", "orderNo": "R-2"}, expect=(422, "user_unreachable"))
    send(F, "seed-food-deleted", "u12", "seed-order-confirmed", {"name": "Dina", "orderNo": "R-3"}, expect=(422, "user_unreachable"))
    # signing in again does not help a suspended user: the gateway refuses the session
    if not done("suspended-login"):
        tok = login_user(UK["u11"])
        st, r = call("GET", G + "/api/v1/me", None, {"Authorization": "Bearer " + tok})
        ok("a suspended user who signs in again is refused by the gateway (403 account_suspended)", st == 403 and r.get("error") == "account_suspended", f"{st} {r}")
        mark("suspended-login")


def stage_killed_partner():
    out("== killed app (partner request)")
    KL = "demo-killed"
    left = 17 - (time.time() - config_changed_at[0])
    if left > 0 and config_changed_at[0]:
        time.sleep(left)
    send(KL, "seed-killed-after", "u01", "seed-killed-ping", {"name": "Abebe"}, expect=(403, "app_killed"))


def mailpit_running():
    return bool(sh("docker", "compose", "ps", "--status", "running", "-q", "mailpit").stdout.strip())


def stage_outage():
    """RETRYING and EXPIRED need a provider that fails: stop Mailpit for ~40 seconds, send EMAIL notifications, start it again."""
    out("== delivery failures: RETRYING and EXPIRED (a short Mailpit outage, dev only)")
    F = "demo-food"
    exts = ("seed-food-email-expired", "seed-food-email-retried")
    if all(done(f"send:{F}:{e}") for e in exts):
        out("  already done")
        return
    if not mailpit_running():
        note("mailpit is not running: skipping the outage scenarios")
        return
    sh("docker", "compose", "stop", "mailpit")
    atexit.register(lambda: sh("docker", "compose", "start", "mailpit"))
    try:
        send(F, exts[0], "u07", "seed-order-confirmed", {"name": "Yonas", "orderNo": "E-1"}, ttlSeconds=60)
        send(F, exts[1], "u03", "seed-order-confirmed", {"name": "Hana", "orderNo": "E-2"})
        r = settle(F, exts[1], want="RETRYING", timeout=45)
        ok("with the email provider down the message is RETRYING (30s backoff)", r.get("status") == "RETRYING", r)
        r = settle(F, exts[0], want="EXPIRED", timeout=70)
        ok("ttlSeconds=60: the retry would fall after the deadline -> EXPIRED", r.get("status") == "EXPIRED", r)
    finally:
        sh("docker", "compose", "start", "mailpit")
    for _ in range(30):
        if mailpit_running():
            break
        time.sleep(1)
    time.sleep(3)
    r = settle(F, exts[1], want="SENT", timeout=120)
    ok("Mailpit is back: the retried message is SENT (after 2-3 attempts)", r.get("status") == "SENT", r)


def stage_inbox():
    out("== inbox: some items read, some unread")
    if done("inbox"):
        out("  already done")
        return
    st, items = ucall("u01", "GET", "/api/v1/me/notifications?size=50")
    ok("u01 has inbox items (food + ride)", st == 200 and len(items) >= 4, f"{st} {len(items) if isinstance(items, list) else items}")
    if st == 200 and items:
        st, r = ucall("u01", "PUT", f"/api/v1/me/notifications/{items[-1]['id']}/read")
        ok("u01 read its oldest item", st == 200 and r.get("read") is True, st)
        st, r = ucall("u01", "PUT", f"/api/v1/me/notifications/{items[-2]['id']}/read")
    st, c = ucall("u01", "GET", "/api/v1/me/notifications/unread-count")
    ok("u01 unread count is lower than its total but not zero", st == 200 and 0 < c["count"] < len(items), f"{c} of {len(items)}")
    st, r = ucall("u02", "PUT", "/api/v1/me/notifications/read-all")
    ok("u02 read everything", st == 200 and r["markedRead"] >= 2, r)
    st, c = ucall("u03", "GET", "/api/v1/me/notifications/unread-count")
    ok("u03 keeps an unread item", st == 200 and c["count"] >= 1, c)
    st, r = ucall("u07", "PUT", f"/api/v1/me/notifications/{uuid.uuid4()}/read")
    ok("marking an item that is not yours -> 404", st == 404, st)
    mark("inbox")


# ----------------------------------------------------------------------------------------------------------------- staff
def stage_staff():
    out("== staff members (created by a SUPER_ADMIN through the admin API; Kratos identities that never log in)")
    st, rows = adm("SUPER_ADMIN", "GET", "/staff")
    have = {r["email"]: r for r in rows}
    for name, role in STAFF_MEMBERS:
        email = f"{name}@{STAFF_DOMAIN}"
        if email in have:
            S["staff"][email] = have[email]["id"]
            continue
        st, r = adm("SUPER_ADMIN", "POST", "/staff", {"email": email, "role": role})
        if ok(f"staff {email} ({role}) created", st == 200 and r["role"] == role, f"{st} {json.dumps(r)[:150]}"):
            S["staff"][email] = r["id"]
            save_state()
    admin2 = f"demo-admin-2@{STAFF_DOMAIN}"
    if not done("staff:deactivated") and admin2 in S["staff"]:
        st, r = adm("SUPER_ADMIN", "PATCH", f"/staff/{S['staff'][admin2]}", {"status": "DISABLED"})
        ok("deactivating a staff member who has never signed in -> 503 (BUG: Kratos answers 404 'no sessions' and registry-service reports it as unavailable)", st == 503, st)
        if st == 503:
            # workaround: give the account a Kratos session first (dev mode reads the emailed code from the dev endpoint)
            gate.wait(2)
            staff.email_login(admin2)
            st, r = adm("SUPER_ADMIN", "PATCH", f"/staff/{S['staff'][admin2]}", {"status": "DISABLED"})
        ok("demo-admin-2 deactivated (after it had a session)", st == 200 and r["status"] == "DISABLED", st)
        mark("staff:deactivated")
    # a role change with history: viewer-2 is promoted to ADMIN and back
    v2 = f"demo-viewer-2@{STAFF_DOMAIN}"
    if not done("staff:rolechange") and v2 in S["staff"]:
        adm("SUPER_ADMIN", "PATCH", f"/staff/{S['staff'][v2]}", {"role": "ADMIN"})
        st, r = adm("SUPER_ADMIN", "PATCH", f"/staff/{S['staff'][v2]}", {"role": "VIEWER"})
        ok("demo-viewer-2 promoted and demoted again (two audit rows)", st == 200 and r["role"] == "VIEWER", st)
        mark("staff:rolechange")
    st, r = adm("SUPER_ADMIN", "POST", "/staff", {"email": "not-an-email", "role": "VIEWER"})
    ok("invalid staff email -> 400", st == 400, st)
    st, r = adm("SUPER_ADMIN", "PATCH", f"/staff/{uuid.uuid4()}", {"role": "ADMIN"})
    ok("unknown staff member -> 404", st == 404, st)


# ----------------------------------------------------------------------------------------------------------------- verification
def expected_ratings():
    """Average and count of VISIBLE reviews, recomputed from the specs and the moderation lists (independent of the server)."""
    finals = collections.defaultdict(dict)
    for u in USERS:
        for slug, rating, text in u["reviews"]:
            if rating is None:
                finals[slug].pop(u["k"], None)
            else:
                finals[slug][u["k"]] = rating
    hidden = {(slug, k) for slug, k, steps in HIDE if steps[-1] == "HIDDEN"}
    out_ = {}
    for slug, rows in finals.items():
        vis = [r for k, r in rows.items() if (slug, k) not in hidden]
        out_[slug] = (round(sum(vis) / len(vis), 2) if vis else 0, len(vis))
    return out_


def stage_verify():
    out("== verification through the API (public catalog, backoffice as VIEWER / ADMIN / SUPER_ADMIN)")
    exp = expected_ratings()
    tok = {"Authorization": "Bearer " + utok("u15")}
    st, catalog = call("GET", G + "/api/v1/apps", None, tok)
    by_slug = {a["slug"]: a for a in catalog} if st == 200 else {}
    ok("public catalog lists only LIVE apps (food, ride, market, quota)", st == 200 and {"demo-food", "demo-ride", "demo-market", "demo-quota"} <= set(by_slug) and not ({"demo-flights", "demo-bank", "demo-killed", "demo-paused"} & set(by_slug)), sorted(by_slug))
    for slug in ("demo-food", "demo-ride", "demo-market"):
        want = exp.get(slug, (0, 0))
        a = by_slug.get(slug, {})
        ok(f"catalog rating of {slug} = {want[0]} from {want[1]} visible reviews", abs(float(a.get("ratingAvg", -1)) - want[0]) < 0.006 and a.get("ratingCount") == want[1], f"{a.get('ratingAvg')} / {a.get('ratingCount')}")
    st, rv = call("GET", G + "/api/v1/apps/demo-food/reviews?size=100", None, tok)
    ok("public food reviews exclude hidden ones and carry no user id", st == 200 and len(rv) == exp["demo-food"][1] and all("userId" not in r for r in rv), f"{len(rv) if isinstance(rv, list) else rv}")
    st, a = call("GET", G + "/api/v1/apps/demo-paused", None, tok)
    ok("a PAUSED app is not in the public catalog (404)", st == 404, st)

    out(" -- as a VIEWER")
    v_user = None
    st, lst = adm("VIEWER", "GET", "/users?size=100")
    ok("viewer: user list works and masks contact details", st == 200 and all("*" in (x["phone"] or "*") and "*" in (x["email"] or "*") for x in lst["items"]), st)
    mine = [x for x in lst["items"] if x["phone"] and x["phone"].startswith("+251") and x["firstName"] and x["firstName"].startswith("Demo")]
    ok("viewer sees the demo users", len(mine) >= 10, len(mine))
    st, d = adm("VIEWER", "GET", f"/users/{uid('u01')}")
    ok("viewer: detail masks phone/email, hides extra values, gender and birth date", st == 200 and d["masked"] and "*" in d["phone"] and d["extra"] is None and d["extraKeys"] and d["gender"] is None and d["dateOfBirth"] is None, d.get("extraKeys"))
    ok("viewer: detail shows device and alias counts and mutes", d["devices"]["active"] == 2 and d["aliasCount"] == 6 and len(d["mutePreferences"]) == 1, f"{d['devices']} {d['aliasCount']}")
    for method, path, body, label in (("POST", f"/users/{uid('u02')}/status", {"status": "SUSPENDED", "reason": "viewer test"}, "viewer cannot suspend a user"),
                                      ("GET", "/audit", None, "viewer cannot read the audit log"), ("GET", "/staff", None, "viewer cannot list staff"),
                                      ("PATCH", f"/apps/{app_id('demo-food')}", {"priority": 10}, "viewer cannot edit an app"),
                                      ("PATCH", f"/reports/{uuid.uuid4()}", {"status": "REVIEWED"}, "viewer cannot handle a report"),
                                      ("POST", f"/apps/{app_id('demo-food')}/keys/generate", {"environment": "DEV"}, "viewer cannot generate a key")):
        st, r = adm("VIEWER", method, path, body)
        ok(label + " (403 insufficient_role)", st == 403 and r.get("error") == "insufficient_role", f"{st} {r}")
    for path, label in (("/apps", "viewer reads the app list"), ("/reports?status=OPEN", "viewer reads reports"), ("/reviews", "viewer reads reviews"),
                        ("/notification/notifications?limit=5", "viewer reads the notification log"), (f"/notification/templates?appId={app_id('demo-food')}", "viewer reads templates"),
                        (f"/apps/{app_id('demo-food')}", "viewer reads an app with its keys"), ("/dashboard", "viewer reads the dashboard"), ("/system", "viewer reads system info")):
        st, r = adm("VIEWER", "GET", path)
        ok(label, st == 200, st)

    out(" -- as an ADMIN")
    st, d = adm("ADMIN", "GET", f"/users/{uid('u01')}")
    ok("admin: detail has full phone, email, extra, gender, birth date", st == 200 and not d["masked"] and d["phone"] == UK["u01"]["phone"] and d["extra"] and d["gender"] == "MALE" and d["dateOfBirth"] == "1988-03-14", d.get("masked"))
    st, d = adm("ADMIN", "GET", f"/users/{uid('u02')}")
    ok("admin: location has no coordinates, only 'hasPreciseLocation'", st == 200 and "latitude" not in json.dumps(d), d.get("location"))
    st, r = adm("ADMIN", "GET", "/staff")
    ok("admin cannot list staff (403)", st == 403, st)
    st, r = adm("ADMIN", "GET", "/audit?limit=5")
    ok("admin reads the audit log", st == 200 and len(r) > 0, st)
    st, r = adm("ADMIN", "POST", f"/apps/{app_id('demo-killed')}/status", {"status": "LIVE"})
    ok("admin cannot revive a KILLED app (403 only_super_admin_can_revive_killed_app)", st == 403 and "super_admin" in json.dumps(r), f"{st} {r}")
    st, r = adm("ADMIN", "POST", f"/users/{uid('u02')}/status", {"status": "ACTIVE"})
    ok("admin may change user status (no-op ACTIVE -> ACTIVE answers 200, nothing audited)", st == 200, st)
    st, r = adm("ADMIN", "POST", "/staff", {"email": "x@adera.local", "role": "VIEWER"})
    ok("admin cannot create staff (403)", st == 403, st)

    out(" -- as a SUPER_ADMIN: counts")
    st, stats = adm("SUPER_ADMIN", "GET", "/users/stats")
    ok("user stats: 1 suspended and 1 deleted among the demo users", st == 200 and stats["suspended"] >= 1 and stats["deleted"] >= 1, stats)
    st, rows = adm("SUPER_ADMIN", "GET", "/staff")
    mine = [r for r in rows if r["email"].startswith("demo-")]
    ok("staff screen: 2 viewers + 2 admins, one DISABLED", len(mine) == 4 and sorted(r["role"] for r in mine) == ["ADMIN", "ADMIN", "VIEWER", "VIEWER"] and sum(r["status"] == "DISABLED" for r in mine) == 1, [(r["email"], r["role"], r["status"]) for r in mine])
    st, a = adm("SUPER_ADMIN", "GET", "/audit?limit=200")
    actions = collections.Counter(e["action"] for e in a)
    roles = collections.Counter(e["actorRole"] for e in a)
    ok("audit log has varied actions and actor roles", len(actions) >= 8 and {"ADMIN", "SUPER_ADMIN"} <= set(roles), f"{len(actions)} actions {dict(roles)}")
    out("   audit actions (latest 200): " + ", ".join(f"{k} x{v}" for k, v in sorted(actions.items())))
    st, stat = adm("SUPER_ADMIN", "GET", "/notification/stats")
    ok("notification stats count every state we produced", st == 200 and {"SENT", "SUPPRESSED", "EXPIRED"} <= set(stat["byStatus"]), stat["byStatus"])
    out("   notifications by status: " + json.dumps(stat["byStatus"]) + " by channel " + json.dumps(stat["byChannel"]))
    for slug in ("demo-food", "demo-ride", "demo-quota", "demo-killed"):
        st, usage = adm("SUPER_ADMIN", "GET", f"/notification/usage?appId={app_id(slug)}")
        out(f"   usage {slug}: " + json.dumps({u['channel']: (int(u['today']), int(u['lifetime'])) for u in usage}))
    st, rows = adm("SUPER_ADMIN", "GET", "/notification/notifications?status=RETRYING&limit=50")
    out(f"   RETRYING right now: {len(rows)}")
    st, rows = adm("SUPER_ADMIN", "GET", "/notification/notifications?limit=200&appId=" + app_id("demo-food"))
    pending = [r for r in rows if r["externalId"] in ("seed-food-cb-500", "seed-food-cb-refused", "seed-food-cb-invalid")]
    for r in pending:
        st, d = adm("SUPER_ADMIN", "GET", f"/notification/notifications/{r['id']}")
        out(f"   callback of {r['externalId']}: " + json.dumps([{k: c[k] for k in ('status', 'attempts', 'lastHttpStatus', 'lastError')} for c in d["callbacks"]]))
    st, rows = adm("SUPER_ADMIN", "GET", "/reports?size=100")
    cnt = collections.Counter((r["status"]) for r in rows)
    ok("reports in every state", {"OPEN", "REVIEWED", "DISMISSED"} <= set(cnt), dict(cnt))
    reasons = {r["reason"] for r in rows}
    ok("reports use all five reasons", reasons >= {"SPAM", "SCAM", "INAPPROPRIATE", "BROKEN", "OTHER"}, sorted(reasons))
    st, rows = adm("SUPER_ADMIN", "GET", "/reviews?size=100")
    ok("backoffice reviews include hidden ones", st == 200 and any(r["status"] == "HIDDEN" for r in rows), len(rows))
    apps = find_apps()
    for slug in ("demo-food", "demo-ride", "demo-market", "demo-paused"):
        want = exp.get(slug, (0, 0))
        a = apps[slug]
        ok(f"backoffice rating of {slug}: {a['ratingAvg']} from {a['ratingCount']}", abs(float(a["ratingAvg"]) - want[0]) < 0.006 and a["ratingCount"] == want[1])
    st, rows = adm("SUPER_ADMIN", "GET", f"/apps/{app_id('demo-paused')}/reviews")
    ok("demo-paused keeps its reviews (one hidden) even though the app is paused", st == 200 and len(rows) == 2, len(rows))
    stage_counts()


def stage_counts():
    out("== counts")
    ids_u = in_list(v["id"] for v in S["users"].values())
    aids = in_list(a["id"] for a in S["apps"].values())
    out("   users:            " + psql("users", f"SELECT status || '=' || count(*) FROM app_user WHERE id IN ({ids_u}) GROUP BY status ORDER BY status").replace("\n", ", "))
    out("   with avatar:      " + psql("users", f"SELECT count(*) FROM app_user WHERE id IN ({ids_u}) AND avatar_image_id IS NOT NULL"))
    out("   devices:          " + psql("users", f"SELECT 'active=' || count(*) FILTER (WHERE is_active) || ' inactive=' || count(*) FILTER (WHERE NOT is_active) FROM device WHERE user_id IN ({ids_u})"))
    out("   aliases:          " + psql("users", f"SELECT count(*) || ' (' || count(DISTINCT app_id) || ' apps)' FROM app_user_alias WHERE user_id IN ({ids_u})"))
    out("   mute prefs:       " + psql("users", f"SELECT count(*) FROM app_notification_preference WHERE user_id IN ({ids_u})"))
    out("   reviews per app:  " + psql("registry", f"SELECT string_agg(a.slug || '=' || n::text || '(' || v::text || ' visible)', ', ') FROM (SELECT app_id, count(*) n, count(*) FILTER (WHERE status='VISIBLE') v FROM app_review WHERE user_id IN ({ids_u}) GROUP BY app_id) r JOIN mini_app a ON a.id = r.app_id"))
    out("   reports per app:  " + psql("registry", f"SELECT string_agg(a.slug || ' ' || s, '; ') FROM (SELECT app_id, string_agg(status || '=' || n::text, ',') s FROM (SELECT app_id, status, count(*) n FROM app_report WHERE user_id IN ({ids_u}) GROUP BY app_id, status) x GROUP BY app_id) r JOIN mini_app a ON a.id = r.app_id"))
    out("   partner keys:     " + psql("registry", f"SELECT string_agg(a.slug || ' ' || k.environment || ':' || k.status, ', ') FROM partner_key k JOIN mini_app a ON a.id = k.app_id WHERE k.label LIKE '{LABEL}%'"))
    out("   templates:        " + psql("notification", "SELECT count(*) || ' (' || count(*) FILTER (WHERE NOT active) || ' inactive)' FROM template WHERE code LIKE 'seed-%'"))
    out("   notifications:    " + psql("notification", "SELECT string_agg(status || '=' || n::text, ', ') FROM (SELECT status, count(*) n FROM notification WHERE external_id LIKE 'seed-%' GROUP BY status ORDER BY status) x"))
    out("   by channel:       " + psql("notification", "SELECT string_agg(channel || '=' || n::text, ', ') FROM (SELECT channel, count(*) n FROM notification WHERE external_id LIKE 'seed-%' GROUP BY channel ORDER BY channel) x"))
    out("   callbacks:        " + psql("notification", "SELECT coalesce(string_agg(status || '=' || n::text, ', '), 'none') FROM (SELECT c.status, count(*) n FROM callback c JOIN notification n ON n.id = c.notification_id WHERE n.external_id LIKE 'seed-%' GROUP BY c.status) x"))
    out("   inbox items:      " + psql("notification", f"SELECT count(*) || ' (' || count(*) FILTER (WHERE read_at IS NOT NULL) || ' read)' FROM inbox_item WHERE user_id IN ({ids_u})"))
    out("   staff (demo-):    " + psql("registry", "SELECT string_agg(role || '/' || status, ', ') FROM staff WHERE email LIKE 'demo-%'"))


# ----------------------------------------------------------------------------------------------------------------- remove
def stage_remove():
    out("== removing everything seed_scenarios created")
    emails = {f"{n}@{STAFF_DOMAIN}" for n, _ in STAFF_MEMBERS}
    # users: Kratos identities by their fixed identifiers, then their rows, reviews, reports, inbox, avatars
    user_ids = {}
    for u in USERS:
        found = kratos_find(u["phone"] or u["email"])
        if found:
            user_ids[u["k"]] = found["id"]
    for k, v in S["users"].items():
        user_ids.setdefault(k, v["id"])
    # also rows whose Kratos identity is already gone
    for u in USERS:
        if u["phone"]:
            r = psql("users", f"SELECT id FROM app_user WHERE phone_e164 = {sqlq(u['phone'])}")
            if r:
                user_ids.setdefault(u["k"], r.splitlines()[0])
        elif u["email"]:
            r = psql("users", f"SELECT id FROM app_user WHERE email = {sqlq(u['email'])}")
            if r:
                user_ids.setdefault(u["k"], r.splitlines()[0])
    ids = list(user_ids.values())
    app_rows = psql("registry", "SELECT id || '|' || slug FROM mini_app WHERE slug IN ('demo-quota','demo-killed','demo-paused')").splitlines()
    new_app_ids = [r.split("|")[0] for r in app_rows]
    demo_apps = [r for r in psql("registry", "SELECT id FROM mini_app WHERE slug LIKE 'demo-%'").splitlines()]
    out(f"  {len(ids)} users, {len(new_app_ids)} extra apps, {len(emails)} staff members")
    # notifications of the seeding (external ids seed-*), on any app
    nids = psql("notification", "SELECT id FROM notification WHERE external_id LIKE 'seed-%'").splitlines()
    if nids:
        psql("notification", f"DELETE FROM callback WHERE notification_id IN ({in_list(nids)})")
        psql("notification", f"DELETE FROM notification_attempt WHERE notification_id IN ({in_list(nids)})")
        psql("notification", f"DELETE FROM inbox_item WHERE notification_id IN ({in_list(nids)})")
        psql("notification", f"DELETE FROM notification WHERE id IN ({in_list(nids)})")
    if ids:
        psql("notification", f"DELETE FROM inbox_item WHERE user_id IN ({in_list(ids)})")
    for aid_ in demo_apps:  # usage counters of those apps (the apps' other notifications, if any, are not touched)
        left = psql("notification", f"SELECT count(*) FROM notification WHERE app_id = {sqlq(aid_)}")
        if left == "0":
            for t in ("usage_daily", "usage_total"):
                psql("notification", f"DELETE FROM {t} WHERE app_id = {sqlq(aid_)}")
    psql("notification", "DELETE FROM template WHERE code LIKE 'seed-%'")
    # partner keys of ours, allowed addresses of the five older apps back to empty
    psql("registry", f"DELETE FROM partner_key WHERE label LIKE '{LABEL}%'")
    for slug in ("demo-food", "demo-ride", "demo-market", "demo-flights", "demo-bank"):
        r = psql("registry", f"SELECT id FROM mini_app WHERE slug = {sqlq(slug)}")
        if r:
            psql("registry", f"UPDATE app_environment SET allowed_cidrs = '[]'::jsonb, updated_at = now() WHERE app_id = {sqlq(r)}")
    # reviews and reports of our users, ratings recomputed with the service's own formula
    if ids:
        touched_apps = psql("registry", f"SELECT DISTINCT app_id FROM app_review WHERE user_id IN ({in_list(ids)})").splitlines()
        psql("registry", f"DELETE FROM app_review WHERE user_id IN ({in_list(ids)})")
        psql("registry", f"DELETE FROM app_report WHERE user_id IN ({in_list(ids)})")
        for a in touched_apps:
            psql("registry", f"""UPDATE mini_app SET
              rating_avg = COALESCE((SELECT ROUND(AVG(rating)::numeric, 2) FROM app_review WHERE app_id = {sqlq(a)} AND status = 'VISIBLE'), 0),
              rating_count = (SELECT COUNT(*) FROM app_review WHERE app_id = {sqlq(a)} AND status = 'VISIBLE') WHERE id = {sqlq(a)}""")
    # avatars and logos in object storage
    try:
        for line in open(os.path.join(ROOT, ".env")):
            if line.startswith("RUSTFS_ACCESS_KEY=") or line.startswith("RUSTFS_SECRET_KEY="):
                os.environ[line.split("=", 1)[0]] = line.split("=", 1)[1].strip()
        import s3tool
        gone = 0
        for prefix in [f"avatars/{i}/" for i in ids] + [f"apps/{i}/" for i in new_app_ids]:
            for key in s3tool.keys(prefix):
                s3tool.call("DELETE", "/" + s3tool.BUCKET + "/" + key)
                gone += 1
        out(f"  deleted {gone} stored image(s)")
    except Exception as e:  # keep removing even if object storage is unreachable
        out(f"  object storage cleanup skipped: {e}")
    # users
    for k, i in user_ids.items():
        staff.call("DELETE", f"{KRATOS_ADMIN}/admin/identities/{i}")
    if ids:
        for t in ("device", "app_user_alias", "app_notification_preference"):
            psql("users", f"DELETE FROM {t} WHERE user_id IN ({in_list(ids)})")
        psql("users", f"DELETE FROM app_user WHERE id IN ({in_list(ids)})")
    # staff members
    staff_ids = []
    for e in emails:
        found = kratos_find(e)
        r = psql("registry", f"SELECT identity_id FROM staff WHERE lower(email) = {sqlq(e)}")
        for i in {*([found["id"]] if found else []), *r.splitlines()}:
            staff_ids.append(i)
            staff.call("DELETE", f"{KRATOS_ADMIN}/admin/identities/{i}")
        psql("registry", f"DELETE FROM staff WHERE lower(email) = {sqlq(e)}")
    # one-time-code send log rows of the fake addresses (they would count against the per-recipient hourly limits next time)
    hashes = [hashlib.sha256(x.strip().lower().encode()).hexdigest() for x in [*(u["phone"] for u in USERS if u["phone"]), *(u["email"] for u in USERS if u["email"]), *emails]]
    psql("notification", f"DELETE FROM send_log WHERE recipient_hash IN ({in_list(hashes)})")
    # the new apps
    psql("registry", f"DELETE FROM mini_app WHERE id IN ({in_list(new_app_ids)})")
    # audit rows: about our things, or made by our temporary staff accounts
    targets = [*ids, *new_app_ids, *staff_ids, *S["keys"].values(), *S["templates"].values()]
    psql("registry", f"DELETE FROM audit_event WHERE target_id IN ({in_list(targets)}) OR actor_id IN ({in_list(S['actors'])})")
    for f in (STATE_PATH,):
        if os.path.exists(f):
            os.remove(f)
    if os.path.isdir(KEYS_DIR):
        for f in os.listdir(KEYS_DIR):
            os.remove(os.path.join(KEYS_DIR, f))
        os.rmdir(KEYS_DIR)
    left = {
        "users": psql("users", "SELECT count(*) FROM app_user"),
        "demo users in Kratos": str(sum(1 for u in USERS if kratos_find(u["phone"] or u["email"]))),
        "reviews": psql("registry", "SELECT count(*) FROM app_review"),
        "reports": psql("registry", "SELECT count(*) FROM app_report"),
        "seed notifications": psql("notification", "SELECT count(*) FROM notification WHERE external_id LIKE 'seed-%'"),
        "seed templates": psql("notification", "SELECT count(*) FROM template WHERE code LIKE 'seed-%'"),
        "seed keys": psql("registry", f"SELECT count(*) FROM partner_key WHERE label LIKE '{LABEL}%'"),
        "extra apps": psql("registry", "SELECT count(*) FROM mini_app WHERE slug IN ('demo-quota','demo-killed','demo-paused')"),
        "demo staff": psql("registry", "SELECT count(*) FROM staff WHERE email LIKE 'demo-%'"),
        "apps (kept)": psql("registry", "SELECT count(*) FROM mini_app"),
    }
    out("  left over: " + ", ".join(f"{k}={v}" for k, v in left.items()))
    out("  done. The five apps from seed_demo.py are untouched (their rating, allowed addresses and any template/key of ours were reset).")


# ----------------------------------------------------------------------------------------------------------------- main
def main(argv):
    only = None
    if "--only" in argv:
        only = set(argv[argv.index("--only") + 1].split(","))
    def want(stage):
        return only is None or stage in only
    require_dev()
    if "--remove" in argv:
        stage_remove()
        return 0
    if "--check" in argv:
        stage_verify()
        out(f"\n{passed} passed, {failed} failed")
        return 1 if failed else 0
    try:
        if want("apps"):
            stage_apps()
        if want("users"):
            stage_users()
            stage_user_negatives()
        if want("moderation"):
            stage_moderation()
        if want("keys"):
            stage_keys()
        if want("templates"):
            stage_templates()
        else:
            for slug, specs in TEMPLATES.items():  # ids are needed by later stages
                for t in adm("ADMIN", "GET", f"/notification/templates?appId={app_id(slug)}")[1]:
                    TPL[(slug, t["code"])] = t["id"]
        if want("partner"):
            stage_partner()
            stage_inbox()
        if want("app-history"):
            stage_app_history()
        if want("partner"):
            stage_partner_state_changes()
            stage_killed_partner()
            if "--no-outage" not in argv:
                stage_outage()
        if want("staff"):
            stage_staff()
        if want("verify"):
            stage_verify()
    finally:
        for h in sessions.values():
            h.close()
        if hook_server[0]:
            hook_server[0].shutdown()
    for n in notes:
        pass
    out(f"\n{passed} passed, {failed} failed, {len(notes)} notes")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
