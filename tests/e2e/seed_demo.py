"""Adds five demo mini apps to the dev backoffice, through the real admin API (so every value is validated and audited).
Safe to run again: apps are found by slug and updated, nothing is duplicated. Dev data only: no partner keys, users or notifications.

Signs in with a TEMPORARY staff account created for the run and removed afterwards (tests/e2e/tempstaff.py), so nobody has to
type a code. To use a real account instead set ADERA_STAFF_EMAIL (then type the 6-digit authenticator code, or set ADERA_TOTP).
Run from the repo root:   python3 -I tests/e2e/seed_demo.py
The websites are real, public, third-party sites used only as link targets so the dashboard has something to show."""
import json, os, struct, sys, uuid, zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import staff, tempstaff

G = staff.G
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def env_value(name):
    for line in open(os.path.join(ROOT, ".env")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()


def png(color, size=256):
    row = b"\x00" + bytes(color) * size
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * size)) + chunk(b"IEND", b""))


def session():
    """A temporary ADMIN staff account (removed when the script ends; the audit rows it caused are kept as the history of the
    seeding). ADERA_STAFF_EMAIL=<address> uses that real account instead: the emailed code is read from the dev endpoint and
    you type the authenticator code (or set ADERA_TOTP=123456). The bootstrap account is never used automatically."""
    global handle
    handle = tempstaff.script_session("ADMIN", keep_audit=True)
    return handle.token


# capability reasons are shown to users when they are asked to allow a permission (English required, Amharic optional)
R = {
    "user.full_name": {"en": "To address you by name", "am": "በስምዎ ለመጥራት"},
    "user.contact": {"en": "To reach you about your order", "am": "ስለ ትዕዛዝዎ ለማነጋገር"},
    "user.address": {"en": "To deliver to the address you pick", "am": "ወደመረጡት አድራሻ ለማድረስ"},
    "location.coarse": {"en": "To show what is available in your city", "am": "በከተማዎ ያለውን ለማሳየት"},
    "location.fine": {"en": "To find the closest driver to you", "am": "ለእርስዎ ቅርብ የሆነውን አሽከርካሪ ለማግኘት"},
    "pay.request": {"en": "To ask you to pay for your order", "am": "ለትዕዛዝዎ ክፍያ ለመጠየቅ"},
}

APPS = [
    dict(slug="demo-food", color=(15, 157, 88), status="LIVE", priority=10,
         name={"en": "Food Delivery", "am": "ምግብ አቅርቦት"},
         description={"en": "Order food from restaurants in Addis Ababa (demo entry: link goes to Deliver Addis).",
                      "am": "በአዲስ አበባ ከሚገኙ ምግብ ቤቶች ምግብ ይዘዙ።"},
         brandColor="#0F9D58", categories=["food", "delivery"], publisherName="Demo Publisher: Food",
         supportEmail="support-food@example.com", website="https://deliveraddis.com", entryUrl="https://deliveraddis.com",
         origins=["https://deliveraddis.com"],
         caps={"user.basic": "GRANTED", "user.full_name": "GRANTED", "user.contact": "GRANTED", "user.address": "GRANTED",
               "location.coarse": "GRANTED", "location.fine": "DENIED", "pay.request": "GRANTED",
               "notify.inapp": "GRANTED", "notify.sms": "GRANTED", "notify.email": "GRANTED"},
         limits={"SMS": dict(maxPerUserPerDay=3, maxPerDay=500, maxDistinctUsersPerDay=300, maxTotal=5000),
                 "EMAIL": dict(maxPerUserPerDay=5, maxPerDay=1000, maxDistinctUsersPerDay=1000, maxTotal=None),
                 "IN_APP": dict(maxPerUserPerDay=20, maxPerDay=10000, maxDistinctUsersPerDay=10000, maxTotal=None)}),
    dict(slug="demo-ride", color=(30, 120, 200), status="LIVE", priority=20,
         name={"en": "Ride Hailing", "am": "የመኪና ጥሪ"},
         description={"en": "Book a ride in minutes (demo entry: link goes to ZayRide).", "am": "በደቂቃዎች ውስጥ ጉዞ ይዘዙ።"},
         brandColor="#1E78C8", categories=["travel", "transport"], publisherName="Demo Publisher: Ride",
         supportEmail="support-ride@example.com", website="https://www.zayride.com", entryUrl="https://www.zayride.com",
         origins=["https://www.zayride.com"],
         caps={"user.basic": "GRANTED", "user.full_name": "GRANTED", "user.contact": "DENIED", "location.fine": "GRANTED",
               "pay.request": "GRANTED", "notify.inapp": "GRANTED", "notify.sms": "GRANTED", "notify.email": "SUSPENDED"},
         limits={"SMS": dict(maxPerUserPerDay=6, maxPerDay=2000, maxDistinctUsersPerDay=1000, maxTotal=20000),
                 "IN_APP": dict(maxPerUserPerDay=50, maxPerDay=50000, maxDistinctUsersPerDay=50000, maxTotal=None)}),
    dict(slug="demo-market", color=(235, 150, 20), status="LIVE", priority=30,
         name={"en": "Marketplace", "am": "ገበያ"},
         description={"en": "Buy and sell near you (demo entry: link goes to Jiji Ethiopia).", "am": "በአቅራቢያዎ ይግዙ ይሽጡ።"},
         brandColor="#EB9614", categories=["shopping", "classifieds"], publisherName="Demo Publisher: Market",
         supportEmail="support-market@example.com", website="https://jiji.com.et", entryUrl="https://jiji.com.et",
         origins=["https://jiji.com.et"],
         caps={"user.basic": "GRANTED", "user.contact": "GRANTED", "pay.request": "DENIED", "notify.inapp": "GRANTED",
               "notify.email": "GRANTED", "notify.sms": "GRANTED"},
         limits={"SMS": dict(maxPerUserPerDay=0, maxPerDay=0, maxDistinctUsersPerDay=0, maxTotal=0),  # 0 = none allowed
                 "EMAIL": dict(maxPerUserPerDay=None, maxPerDay=None, maxDistinctUsersPerDay=None, maxTotal=None)}),  # null = unlimited
    dict(slug="demo-flights", color=(160, 30, 60), status="DRAFT", priority=40,
         name={"en": "Flights", "am": "በረራዎች"},
         description={"en": "Search and book flights (demo entry: link goes to Ethiopian Airlines). Still a draft.", "am": "በረራዎችን ይፈልጉ ይያዙ።"},
         brandColor="#A01E3C", categories=["travel"], publisherName="Demo Publisher: Flights",
         supportEmail="support-flights@example.com", website="https://www.ethiopianairlines.com", entryUrl="https://www.ethiopianairlines.com",
         origins=["https://www.ethiopianairlines.com"],
         caps={"user.basic": "GRANTED", "user.full_name": "GRANTED", "notify.inapp": "GRANTED"}, limits={}),
    dict(slug="demo-bank", color=(90, 60, 160), status="PAUSED", priority=50,
         name={"en": "Bank Transfer", "am": "የባንክ ዝውውር"},
         description={"en": "Send money from your account (demo entry: link goes to Dashen Bank). Paused.", "am": "ከሂሳብዎ ገንዘብ ይላኩ።"},
         brandColor="#5A3CA0", categories=["finance"], publisherName="Demo Publisher: Bank",
         supportEmail="support-bank@example.com", website="https://dashenbanksc.com", entryUrl="https://dashenbanksc.com",
         origins=["https://dashenbanksc.com"],
         caps={"user.basic": "GRANTED", "pay.request": "GRANTED", "notify.sms": "SUSPENDED"}, limits={}),
]

token = session()
A = {"Authorization": "Bearer " + token}
ok = fail = 0


def call(method, path, body=None, expect=(200, 201)):
    global ok, fail
    st, r = staff.call(method, G + "/admin/api/v1" + path, body, A)
    if st in expect:
        ok += 1
    else:
        fail += 1
        print(f"  FAIL {method} {path} -> {st} {json.dumps(r)[:200]}")
    return r


def upload_logo(app_id, color):
    boundary = "----adera" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="logo.png"\r\nContent-Type: image/png\r\n\r\n').encode() \
        + png(color) + f"\r\n--{boundary}--\r\n".encode()
    import urllib.request, urllib.error
    req = urllib.request.Request(f"{G}/admin/api/v1/apps/{app_id}/logo", data=body, method="POST",
                                 headers={**A, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as e:
        global fail
        fail += 1
        print("  FAIL logo ->", e.code, e.read()[:150])


existing = {a["slug"]: a for a in staff.call("GET", G + "/admin/api/v1/apps", None, A)[1]}
for spec in APPS:
    print(spec["slug"])
    fields = {k: spec[k] for k in ("name", "description", "brandColor", "categories", "publisherName", "supportEmail", "website", "entryUrl", "priority")}
    fields["allowedOrigins"] = spec["origins"]
    fields["markets"] = ["ET"]
    if spec["slug"] in existing:
        app_id = existing[spec["slug"]]["id"]
        call("PATCH", f"/apps/{app_id}", fields)
    else:
        app_id = call("POST", "/apps", {"slug": spec["slug"], **fields}).get("id")
    if not app_id:
        continue
    upload_logo(app_id, spec["color"])
    for cap, state in spec["caps"].items():
        body = {"state": state}
        if cap in R:
            body["reason"] = R[cap]
        call("PUT", f"/apps/{app_id}/capabilities/{cap}", body)
    for channel, limit in spec["limits"].items():
        call("PUT", f"/apps/{app_id}/notification-limits/{channel}", limit)
    if spec["status"] != "DRAFT":
        call("POST", f"/apps/{app_id}/status", {"status": "LIVE"})
        if spec["status"] == "PAUSED":
            call("POST", f"/apps/{app_id}/status", {"status": "PAUSED"})

handle.close()  # also runs at exit if the script stops early
print(f"\n{ok} calls succeeded, {fail} failed")
sys.exit(1 if fail else 0)
