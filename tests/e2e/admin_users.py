"""Staff user management through the GATEWAY: list/search/detail, suspend -> the user is refused by the gateway
(403 account_suspended), reactivate, mark deleted (403 account_deleted, final), audit rows. Cleans up after itself.
Needs DEV mode. Run from the repo root:  python3 -I tests/e2e/admin_users.py   (creates and removes a temporary staff account)"""
import json, os, subprocess, sys, time, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import staff, tempstaff

G = staff.G; K = staff.K; N = staff.N
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
passed = failed = 0


def ok(label, cond, extra=""):
    global passed, failed
    passed, failed = (passed + 1, failed) if cond else (passed, failed + 1)
    print(("  PASS " if cond else "  FAIL ") + label + (f"  [{extra}]" if extra else ""))


def env_value(name):
    for line in open(os.path.join(ROOT, ".env")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()


def psql(db, sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", env_value("POSTGRES_USER"), "-d", db, "-Atc", sql], capture_output=True, text=True, cwd=ROOT).stdout.strip()


def signup(phone):
    staff.call("DELETE", N + "/dev/messages")
    _, f = staff.call("GET", K + "/self-service/registration/api")
    staff.call("POST", f"{K}/self-service/registration?flow={f['id']}", {"method": "code", "traits.phone_number": phone})
    code = staff.otp_from_dev_endpoint(phone)
    _, r = staff.call("POST", f"{K}/self-service/registration?flow={f['id']}", {"method": "code", "traits.phone_number": phone, "code": code})
    return r["session_token"], r["identity"]["id"]


phone = "+2519" + str(int(time.time()))[-8:]
user_token, user_id = signup(phone)
print("user signed up")
# SUPER_ADMIN is needed here: the script checks the unmasked phone number, which only a super admin sees.
# A temporary staff account (removed at exit); ADERA_STAFF_EMAIL switches to a real account.
handle = tempstaff.script_session("SUPER_ADMIN")
S = {"Authorization": "Bearer " + handle.token}
U = {"Authorization": "Bearer " + user_token}
try:
    st, me = staff.call("GET", G + "/api/v1/me", headers=U)
    ok("new user can use the API", st == 200, st)
    st, lst = staff.call("GET", G + "/admin/api/v1/users?q=" + urllib.parse.quote(phone[-8:]), headers=S)
    ok("search by phone digits finds the user", st == 200 and lst.get("total") == 1 and lst["items"][0]["id"] == user_id, st)
    st, d = staff.call("GET", f"{G}/admin/api/v1/users/{user_id}", headers=S)
    ok("detail: full phone for a super admin, no tokens or coordinates", st == 200 and d["phone"] == phone and d["masked"] is False and "fcm" not in json.dumps(d), st)
    st, r = staff.call("POST", f"{G}/admin/api/v1/users/{user_id}/status", {"status": "SUSPENDED"}, headers=S)
    ok("suspending without a reason -> 400 on reason", st == 400 and "reason" in r.get("fields", {}), st)
    st, r = staff.call("POST", f"{G}/admin/api/v1/users/{user_id}/status", {"status": "SUSPENDED", "reason": "e2e test"}, headers=S)
    ok("suspend -> 200 SUSPENDED", st == 200 and r["status"] == "SUSPENDED", st)
    st, r = staff.call("GET", G + "/api/v1/me", headers=U)
    ok("suspending also revokes the user's sessions: old token -> 401", st == 401, st)
    # signing in again works at Kratos (it does not know our status), but the gateway check refuses the new session
    staff.call("DELETE", N + "/dev/messages")
    _, f = staff.call("GET", K + "/self-service/login/api")
    staff.call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "code", "identifier": phone})
    code = staff.otp_from_dev_endpoint(phone)
    _, r = staff.call("POST", f"{K}/self-service/login?flow={f['id']}", {"method": "code", "identifier": phone, "code": code})
    U2 = {"Authorization": "Bearer " + r["session_token"]}
    st, r = staff.call("GET", G + "/api/v1/me", headers=U2)
    ok("signed in again, still refused by the gateway: 403 account_suspended", st == 403 and r.get("error") == "account_suspended", f"{st} {r}")
    st, r = staff.call("POST", f"{G}/admin/api/v1/users/{user_id}/status", {"status": "ACTIVE"}, headers=S)
    ok("reactivate -> 200 ACTIVE", st == 200 and r["status"] == "ACTIVE", st)
    st, r = staff.call("GET", G + "/api/v1/me", headers=U2)
    ok("the new session works again", st == 200, st)
    st, r = staff.call("POST", f"{G}/admin/api/v1/users/{user_id}/status", {"status": "DELETED", "reason": "e2e test"}, headers=S)
    ok("mark deleted -> 200 DELETED with deletedAt", st == 200 and r["status"] == "DELETED" and r["deletedAt"], st)
    st, r = staff.call("POST", f"{G}/admin/api/v1/users/{user_id}/status", {"status": "ACTIVE"}, headers=S)
    ok("deleted is final -> 409 user_deleted", st == 409 and r.get("error") == "user_deleted", st)
    st, r = staff.call("GET", f"{G}/admin/api/v1/audit?target={user_id}&action=user.", headers=S)
    ok("three audit rows (suspend, reactivate, delete)", st == 200 and len(r) == 3 and r[0]["meta"]["to"] == "DELETED", f"{st} {len(r) if isinstance(r, list) else r}")
    st, r = staff.call("GET", G + "/admin/api/v1/users/stats", headers=S)
    ok("stats", st == 200 and r["deleted"] >= 1, r)
finally:
    print("cleanup")
    subprocess.run(["curl", "-s", "-X", "DELETE", f"http://127.0.0.1:4434/admin/identities/{user_id}"], capture_output=True)
    for t in ("app_user_alias", "device", "app_notification_preference"):
        psql("users", f"DELETE FROM {t} WHERE user_id='{user_id}'")
    psql("users", f"DELETE FROM app_user WHERE id='{user_id}'")
    psql("registry", f"DELETE FROM audit_event WHERE target_id='{user_id}'")
    handle.close()
print(f"\n{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
