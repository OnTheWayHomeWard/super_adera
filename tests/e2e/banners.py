"""Banners end to end through the GATEWAY: staff create, upload the picture, publish, pause and delete with a TEMPORARY ADMIN
(tests/e2e/tempstaff.py), the super app reads the public list WITHOUT signing in, and a temporary VIEWER cannot change anything.

Checks: publish needs an image; three JPEG sizes, the signed links load from RustFS; the public list (filters, limit, order, cache
header, no login); a scheduled, an ended, a paused and a draft banner are not listed; a banner of a suspended (PAUSED) mini app
vanishes by itself and comes back when the app is LIVE again; external links only to the allowed domains (BANNER_EXTERNAL_DOMAINS);
reorder; audit rows; a VIEWER gets 403 on every write.

Run from the repo root:   python3 -I tests/e2e/banners.py
Optional:  BANNER_CAPTURE=/path/out.json  writes every exchange it made (used to capture the real samples of the docs).
Leaves nothing behind (also when it fails): its banners and their files, its temporary mini app (e2e-banners-*) with its logo,
the temporary staff accounts and their audit rows. Never uses the bootstrap staff account. DEV stack only."""
import json, os, struct, subprocess, sys, time, urllib.error, urllib.request, uuid, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import staff, tempstaff

G = staff.G
RUN = "e2e-banners-" + uuid.uuid4().hex[:6]
passed = failed = 0
CAPTURE = {}


def ok(label, cond, extra=""):
    global passed, failed
    passed, failed = (passed + 1, failed) if cond else (passed, failed + 1)
    print(("  PASS " if cond else "  FAIL ") + label + (f"  [{extra}]" if extra != "" else ""))
    return cond


def psql(db, sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "adera_admin", "-d", db, "-Atc", sql],
                          capture_output=True, text=True, cwd=ROOT).stdout.strip()


def raw(method, url, body=None, headers=None, content_type=None):
    """Like staff.call, but also returns the response headers."""
    h = {"accept": "application/json", **(headers or {})}
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        h["content-type"] = content_type or "application/json"
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h), timeout=30)
        payload, code, hdrs = r.read(), r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        payload, code, hdrs = e.read(), e.code, dict(e.headers)
    try:
        parsed = json.loads(payload or b"{}")
    except ValueError:
        parsed = {"raw": payload[:200].decode(errors="replace")}
    return code, parsed, hdrs


def cap(name, st, body, extra=None):
    CAPTURE[name] = {"status": st, "body": body, **(extra or {})}
    return st, body


def png(w, h, rgb):
    rows = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def multipart(content, filename="banner.png", mime="image/png"):
    b = "----adera" + uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode() + content + f"\r\n--{b}--\r\n".encode()
    return body, f"multipart/form-data; boundary={b}"


def iso(seconds_from_now):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + seconds_from_now))


def session(role):
    """A temporary staff account. The gateway allows 10 sign-in submits a minute per IP (shared with anything else signing in):
    on a 429 wait for the next minute and try again."""
    for attempt in range(3):
        try:
            return tempstaff.open_session(role)
        except SystemExit as e:
            if ("429" not in str(e) and "rate limited" not in str(e)) or attempt == 2:
                raise
            print(f"  (sign-in rate limited, waiting a minute before the {role} session)")
            time.sleep(61)


created_banners = []
temp_app = {}


def main():
    tempstaff.require_dev_stack()
    admin = session("ADMIN")
    A = {"Authorization": "Bearer " + admin.token}
    viewer = session("VIEWER")
    V = {"Authorization": "Bearer " + viewer.token}

    def adm(method, path, body=None, headers=None):
        st, r, _ = raw(method, G + "/admin/api/v1" + path, body, {**A, **(headers or {})})
        return st, r

    def new_banner(fields, name=None):
        body = {"title": {"en": f"{RUN} {name or 'banner'}"}, "placement": "PROMOTIONS", "category": "ANNOUNCEMENT", "actionType": "NONE",
                "startsAt": iso(-3600), **fields}
        st, r = adm("POST", "/banners", body)
        if st == 200:
            created_banners.append(r["id"])
        if name:
            cap("create:" + name, st, r, {"request": body})
        return st, r

    def upload(bid, content, name=None, mime="image/png"):
        body, ctype = multipart(content, mime=mime)
        st, r, _ = raw("POST", f"{G}/admin/api/v1/banners/{bid}/image", body, A, ctype)
        if name:
            cap("image:" + name, st, r)
        return st, r

    def public(query=""):
        st, r, hdrs = raw("GET", G + "/api/v1/banners" + query)
        return st, r, hdrs

    def public_ids(query):
        st, r, _ = public(query)
        return [b["id"] for b in r] if st == 200 and isinstance(r, list) else None

    try:
        print("== setup: a temporary LIVE mini app")
        st, app = adm("POST", "/apps", {"slug": RUN, "name": {"en": "E2E Banner App", "am": "ሙከራ"}, "supportEmail": "e2e@example.com",
                                         "entryUrl": "https://e2e.example.com/", "allowedOrigins": ["https://e2e.example.com"]})
        ok("temporary app created", st == 200, st)
        temp_app.update(app)
        body, ctype = multipart(png(128, 128, (200, 60, 60)), "logo.png")
        st, _, _ = raw("POST", f"{G}/admin/api/v1/apps/{app['id']}/logo", body, A, ctype)
        st, r = adm("POST", f"/apps/{app['id']}/status", {"status": "LIVE"})
        ok("temporary app is LIVE", st == 200 and r.get("status") == "LIVE", st)

        print("== settings")
        st, s = adm("GET", "/banners/settings")
        cap("settings", st, s)
        ok("settings list the allowed external domains from BANNER_EXTERNAL_DOMAINS", st == 200 and "adera.et" in s.get("externalDomains", []), s.get("externalDomains"))
        ok("settings list the image sizes", st == 200 and s.get("imageSizes") == {"small": 480, "medium": 960, "large": 1600}, s.get("imageSizes"))

        print("== create, image, publish")
        st, a = new_banner({"title": {"en": f"{RUN} Your first ride is on us", "am": "የመጀመሪያ ጉዞዎ በእኛ ነው"}, "subtitle": {"en": "Up to 150 birr off"},
                            "placement": "HOME_HERO", "category": "FEATURED_APP", "appId": app["id"], "actionType": "MINI_APP",
                            "target": "/promo/first-ride", "priority": 90}, "featured")
        ok("create -> 200 DRAFT, state DRAFT, not visible", st == 200 and a["status"] == "DRAFT" and a["state"] == "DRAFT" and a["visible"] is False, st)
        st, r = adm("POST", f"/banners/{a['id']}/status", {"status": "PUBLISHED"})
        cap("publish-without-image", st, r)
        ok("publishing without an image -> 409 not_ready_to_publish", st == 409 and r.get("error") == "not_ready_to_publish" and r.get("fields", {}).get("missing") == "image", f"{st} {r}")
        st, r = upload(a["id"], b"<svg xmlns='http://www.w3.org/2000/svg'/>", mime="image/svg+xml")
        ok("an SVG is refused (400 on file)", st == 400 and "file" in r.get("fields", {}), f"{st} {r}")
        st, r = upload(a["id"], png(1800, 700, (30, 120, 200)), "featured")
        sizes = {k: (v["width"], v["height"]) for k, v in (r.get("images") or {}).items()}
        ok("upload -> three sizes small 480, medium 960, large 1600", st == 200 and sizes == {"small": (480, 187), "medium": (960, 373), "large": (1600, 622)}, sizes)
        if st == 200:
            try:
                img = urllib.request.urlopen(r["images"]["large"]["url"], timeout=20)
                head = img.read(3)
                ok("the signed link of the large size loads from RustFS as a JPEG", img.status == 200 and img.headers.get("Content-Type") == "image/jpeg" and head[:2] == b"\xff\xd8", img.headers.get("Content-Type"))
            except Exception as e:  # noqa: BLE001
                ok("the signed link of the large size loads from RustFS as a JPEG", False, e)
        st, r = adm("POST", f"/banners/{a['id']}/status", {"status": "PUBLISHED"})
        cap("publish", st, r)
        ok("publish -> PUBLISHED, state LIVE, visible", st == 200 and r["status"] == "PUBLISHED" and r["state"] == "LIVE" and r["visible"] is True, f"{st} {r.get('state')}")

        print("== the public list (no sign-in)")
        st, lst, hdrs = public("?placement=HOME_HERO&limit=50")
        cap("public:HOME_HERO", st, lst, {"headers": {k: v for k, v in hdrs.items() if k.lower() in ("cache-control", "content-type")}})
        mine = [b for b in lst if b["id"] == a["id"]] if st == 200 else []
        ok("GET /api/v1/banners without any token -> 200 and lists it", st == 200 and len(mine) == 1, st)
        ok("cache header: public, 60 s", hdrs.get("Cache-Control") == "max-age=60, public", hdrs.get("Cache-Control"))
        if mine:
            b = mine[0]
            ok("it carries the action, target, linked app (no internal status) and three image links",
               b["actionType"] == "MINI_APP" and b["target"] == "/promo/first-ride" and b["app"]["slug"] == RUN and "status" not in b["app"]
               and b["app"]["logoUrl"] and set(b["images"]) == {"small", "medium", "large"} and "status" not in b and "visible" not in b, b.get("app"))
        ok("category filter FEATURED_APP lists it", a["id"] in (public_ids("?placement=HOME_HERO&category=FEATURED_APP&limit=50") or []))
        ok("category filter EVENT does not", a["id"] not in (public_ids("?category=EVENT&limit=50") or [a["id"]]))
        ok("another placement does not", a["id"] not in (public_ids("?placement=CATALOG_TOP&limit=50") or [a["id"]]))
        st, r, _ = raw("GET", G + "/api/v1/banners?placement=HOME_HERO&limit=50", headers={"X-User-Id": str(uuid.uuid4()), "Authorization": "Bearer nonsense"})
        ok("a client-sent X-User-Id or a bad token changes nothing (still 200)", st == 200 and any(x["id"] == a["id"] for x in r), st)
        st, r, _ = public("?placement=SIDEBAR")
        cap("public:bad-placement", st, r)
        ok("unknown placement -> 400 validation_failed on placement", st == 400 and r.get("error") == "validation_failed" and "placement" in r.get("fields", {}), f"{st} {r}")
        st, r, _ = public("?limit=0")
        ok("limit 0 -> 400 on limit", st == 400 and "limit" in r.get("fields", {}), st)
        st, _, _ = raw("POST", G + "/api/v1/banners", {})
        ok("the public route is read only (POST is not routed)", st == 404, st)

        print("== not shown: scheduled, ended, paused, draft")
        st, sch = new_banner({"placement": "HOME_HERO", "startsAt": iso(86400), "priority": 95}, "scheduled")
        st, end = new_banner({"placement": "HOME_HERO", "startsAt": iso(-2 * 86400), "endsAt": iso(-3600), "priority": 95}, "ended")
        st, pau = new_banner({"placement": "HOME_HERO", "priority": 95}, "paused")
        st, dra = new_banner({"placement": "HOME_HERO", "priority": 95}, "draft")
        for b_ in (sch, end, pau, dra):
            upload(b_["id"], png(800, 300, (90, 160, 90)))
        for b_ in (sch, end, pau):
            adm("POST", f"/banners/{b_['id']}/status", {"status": "PUBLISHED"})
        st, r = adm("POST", f"/banners/{pau['id']}/status", {"status": "PAUSED"})
        cap("pause", st, r)
        ids = public_ids("?placement=HOME_HERO&limit=50") or []
        ok("scheduled (starts tomorrow) is not listed", sch["id"] not in ids)
        ok("ended (end time passed) is not listed", end["id"] not in ids)
        ok("paused is not listed", pau["id"] not in ids)
        ok("draft is not listed", dra["id"] not in ids)
        ok("the live one still is", a["id"] in ids)
        states = {k: adm("GET", f"/banners/{v['id']}")[1].get("state") for k, v in (("scheduled", sch), ("ended", end), ("paused", pau), ("draft", dra))}
        ok("admin states: SCHEDULED, ENDED, PAUSED, DRAFT", states == {"scheduled": "SCHEDULED", "ended": "ENDED", "paused": "PAUSED", "draft": "DRAFT"}, states)
        st, r = adm("GET", "/banners?state=ENDED&placement=HOME_HERO&size=100")
        ok("the admin list filters by the computed state (ENDED)", st == 200 and end["id"] in [x["id"] for x in r] and a["id"] not in [x["id"] for x in r], st)
        st, r = adm("GET", f"/banners?state=LIVE&appId={app['id']}")
        cap("admin-list:LIVE", st, r)
        ok("the admin list filters by state LIVE and app", st == 200 and [x["id"] for x in r] == [a["id"]], [x.get("id") for x in r] if isinstance(r, list) else r)
        st, r = adm("PATCH", f"/banners/{end['id']}", {"endsAt": None})
        cap("extend-ended", st, r, {"request": {"endsAt": None}})
        ok("removing the end time of the ended banner makes it LIVE and listed, nothing else changed", st == 200 and r["state"] == "LIVE" and end["id"] in (public_ids("?placement=HOME_HERO&limit=50") or []), r.get("state"))

        print("== a suspended mini app hides its banners")
        adm("POST", f"/apps/{app['id']}/status", {"status": "PAUSED"})
        ok("app PAUSED -> its banner is no longer listed", a["id"] not in (public_ids("?placement=HOME_HERO&limit=50") or [a["id"]]))
        st, r = adm("GET", f"/banners/{a['id']}")
        cap("admin-get:app-paused", st, r)
        ok("admin: state still LIVE, visible false, app.status PAUSED", r.get("state") == "LIVE" and r.get("visible") is False and r.get("app", {}).get("status") == "PAUSED", f"{r.get('state')} {r.get('visible')}")
        adm("POST", f"/apps/{app['id']}/status", {"status": "LIVE"})
        ok("app LIVE again -> the banner is back by itself", a["id"] in (public_ids("?placement=HOME_HERO&limit=50") or []))

        print("== validation")
        st, r = new_banner({"actionType": "EXTERNAL", "target": "https://evil.test/win"}, "external-refused")
        ok("external link to a domain not on the allow-list -> 400 on target", st == 400 and "evil.test is not allowed" in r.get("fields", {}).get("target", ""), f"{st} {r}")
        st, r = new_banner({"actionType": "EXTERNAL", "target": "http://adera.et/"})
        ok("external http (not https) -> 400", st == 400 and r.get("fields", {}).get("target") == "must be an https:// URL", f"{st} {r}")
        st, ext = new_banner({"actionType": "EXTERNAL", "target": "https://news.adera.et/holiday", "category": "EVENT"}, "external")
        ok("external link to a subdomain of an allowed domain -> 200", st == 200 and ext.get("target") == "https://news.adera.et/holiday", st)
        st, r = new_banner({"actionType": "INTERNAL", "target": "wallet"})
        ok("INTERNAL needs an adera:// link -> 400", st == 400 and "target" in r.get("fields", {}), st)
        st, r = new_banner({"category": "FEATURED_APP"}, "featured-without-app")
        ok("FEATURED_APP without an app -> 400 on appId", st == 400 and "appId" in r.get("fields", {}), st)
        st, r = new_banner({"endsAt": iso(-7200)})
        ok("end before start -> 400 on endsAt", st == 400 and "endsAt" in r.get("fields", {}), st)
        st, r = adm("PATCH", f"/banners/{a['id']}", {"actionType": "NONE"})
        ok("an update is checked as a whole: NONE with the old target left -> 400", st == 400 and "target" in r.get("fields", {}), st)

        print("== order: priority first, reorder")
        st, lst, _ = public("?placement=HOME_HERO&limit=50")
        mine_order = [b["id"] for b in lst if b["id"] in (a["id"], end["id"])]
        ok("priority first (95 before 90)", mine_order == [end["id"], a["id"]], mine_order)
        st, r = adm("POST", "/banners/reorder", {"ids": [a["id"], end["id"]]})
        cap("reorder", st, r, {"request": {"ids": [a["id"], end["id"]]}})
        ok("reorder -> priorities 20 and 10", st == 200 and [x["priority"] for x in r] == [20, 10], [x.get("priority") for x in r] if isinstance(r, list) else r)
        st, lst, _ = public("?placement=HOME_HERO&limit=50")
        ok("the public order follows", [b["id"] for b in lst if b["id"] in (a["id"], end["id"])] == [a["id"], end["id"]])
        st, lst, _ = public("?placement=HOME_HERO&limit=1")
        ok("limit=1 -> one banner", st == 200 and len(lst) == 1, len(lst) if isinstance(lst, list) else lst)

        print("== a VIEWER reads but cannot write")
        st, r, _ = raw("GET", G + "/admin/api/v1/banners?size=5", headers=V)
        ok("viewer lists banners", st == 200, st)
        body, ctype = multipart(png(10, 10, (0, 0, 0)))
        for method, path, b_, label in (("POST", "/banners", {"title": {"en": "x"}}, "create"), ("PATCH", f"/banners/{a['id']}", {"priority": 1}, "update"),
                                        ("POST", f"/banners/{a['id']}/status", {"status": "PAUSED"}, "pause"), ("POST", "/banners/reorder", {"ids": [a["id"]]}, "reorder"),
                                        ("DELETE", f"/banners/{a['id']}", None, "delete")):
            st, r, _ = raw(method, G + "/admin/api/v1" + path, b_, V)
            if label == "create":
                cap("viewer-create", st, r)
            ok(f"viewer cannot {label} (403 insufficient_role)", st == 403 and r.get("error") == "insufficient_role", f"{st} {r}")
        st, r, _ = raw("POST", f"{G}/admin/api/v1/banners/{a['id']}/image", body, V, ctype)
        ok("viewer cannot upload an image (403)", st == 403, st)
        st, r, _ = raw("GET", G + "/admin/api/v1/banners")
        ok("no session -> 401", st == 401, st)

        print("== audit and delete")
        st, r = adm("GET", f"/audit?target={a['id']}&action=banner.")
        acts = sorted({e["action"] for e in r}) if st == 200 else r
        ok("audit has the create, image and status rows of the banner", acts == ["banner.create", "banner.image", "banner.status"], acts)
        st, r = adm("GET", "/audit?action=banner.reorder&limit=1")
        ok("the reorder is audited with the ids in order", st == 200 and r and r[0]["meta"]["ids"] == [a["id"], end["id"]], st)
        st, r = adm("GET", f"/audit?appId={app['id']}&action=banner.")
        ok("those rows also show under the linked app", st == 200 and len(r) >= 3, len(r) if isinstance(r, list) else r)
        large_url = adm("GET", f"/banners/{a['id']}")[1]["images"]["large"]["url"]
        st, r = adm("DELETE", f"/banners/{a['id']}")
        cap("delete", st, r)
        ok("delete -> 200 deleted, then 404", st == 200 and r.get("status") == "deleted" and adm("GET", f"/banners/{a['id']}")[0] == 404, st)
        try:
            urllib.request.urlopen(large_url, timeout=20)
            ok("its image files are gone from RustFS", False, "still served")
        except urllib.error.HTTPError as e:
            ok("its image files are gone from RustFS", e.code in (403, 404), e.code)
    finally:
        print("cleanup")
        for bid in created_banners:
            adm("DELETE", f"/banners/{bid}")
        left = psql("registry", f"SELECT count(*) FROM banner WHERE title->>'en' LIKE '{RUN}%'")
        if temp_app.get("id"):
            adm("DELETE", f"/apps/{temp_app['id']}/logo")
            psql("registry", f"DELETE FROM mini_app WHERE id = '{temp_app['id']}'")
            psql("registry", f"DELETE FROM audit_event WHERE app_id = '{temp_app['id']}' OR target_id = '{temp_app['id']}'")
        psql("registry", "DELETE FROM audit_event WHERE target_type = 'banner' AND target_id IN ("
             + ",".join(f"'{b}'" for b in created_banners or ["-"]) + ")")
        admin.close()
        if viewer:
            viewer.close()
        print("  banners left:", left, "| app left:", psql("registry", f"SELECT count(*) FROM mini_app WHERE slug = '{RUN}'"),
              "| temporary staff left:", psql("registry", "SELECT count(*) FROM staff WHERE email LIKE 'tempstaff-%'"))
        if os.environ.get("BANNER_CAPTURE"):
            with open(os.environ["BANNER_CAPTURE"], "w") as fh:
                json.dump(CAPTURE, fh, indent=2, ensure_ascii=False)
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
