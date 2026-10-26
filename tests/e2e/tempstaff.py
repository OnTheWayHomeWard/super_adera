"""A TEMPORARY dashboard staff account for the e2e scripts, created in the dev stack and removed again.

Why: the bootstrap staff account has a person's authenticator app enrolled, so scripts must never log in as it.
This helper creates its own account (random address, role ADMIN unless asked otherwise) and deletes everything it made:
  - the Kratos identity (admin API on 127.0.0.1:4434) and with it its sessions,
  - the row in registry.staff,
  - the authenticator secret file tests/e2e/.staff-totp-<email>.json,
  - the one-time-code email rows this address caused in notification.send_log,
  - (optional) the audit events the account caused.
It refuses to run unless the stack is in dev mode (notification-service's /dev/messages endpoint answers).

As a module (from a script in tests/e2e):
    import tempstaff
    handle = tempstaff.open_session("ADMIN")      # cleanup also runs at interpreter exit, even if the script fails
    try:
        token = handle.token                      # aal2 session token: {"Authorization": "Bearer " + token}
        ...
    finally:
        handle.close()                            # idempotent
  or:  with tempstaff.temp_staff("ADMIN") as handle: ...
  or, to honour ADERA_STAFF_EMAIL (use a real account + a typed code instead of a temporary one):
        handle = tempstaff.script_session("ADMIN")

CLI (run from the repo root):
    python3 -I tests/e2e/tempstaff.py                 self-test: create, check the admin API answers, remove
    python3 -I tests/e2e/tempstaff.py --role SUPER_ADMIN
    python3 -I tests/e2e/tempstaff.py clean           remove leftovers of an interrupted run (every tempstaff-*@adera.local)
    python3 -I tests/e2e/tempstaff.py create [--role R]   create one that stays (authenticator enrolled, secret file kept) and print
                                                      its address; for tools that sign in themselves (backoffice/tests/ui/run.sh)
    python3 -I tests/e2e/tempstaff.py remove <address>    remove an account made by `create`

Environment:  ADERA_STAFF_EMAIL=<real staff address>   scripts use that account: the one-time code is read from the dev
              endpoint, the authenticator code comes from ADERA_TOTP or a prompt (nothing is created or deleted).
              If tests/e2e/.staff-totp-<address>.json exists for it, that stored secret is used instead of asking."""
import atexit, contextlib, hashlib, json, os, re, subprocess, sys, urllib.parse, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import staff

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
KRATOS_ADMIN = "http://127.0.0.1:4434"
DOMAIN = "adera.local"
PREFIX = "tempstaff-"
ROLES = ("VIEWER", "ADMIN", "SUPER_ADMIN")


def _psql(db, sql):
    r = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "adera_admin", "-d", db, "-v", "ON_ERROR_STOP=1", "-Atc", sql],
                       capture_output=True, text=True, cwd=ROOT)
    if r.returncode != 0:
        raise RuntimeError("psql failed on " + db + ": " + r.stderr.strip()[:300])
    return r.stdout.strip()


def require_dev_stack():
    """Only the dev stack exposes the one-time codes; anything else must never get throw-away admin accounts."""
    st, body = staff.call("GET", staff.N + "/dev/messages")
    if st != 200 or not isinstance(body, list):
        raise SystemExit("refusing to run: notification-service's dev endpoint (" + staff.N + "/dev/messages) is not reachable, "
                         "so this does not look like the dev stack")


def secret_path(email):
    return os.path.join(HERE, ".staff-totp-" + re.sub(r"[^a-z0-9]", "_", email.lower()) + ".json")


class Handle:
    def __init__(self, email, identity_id, token, temporary=True):
        self.email, self.identity_id, self.token, self.temporary = email, identity_id, token, temporary
        self.keep_audit = False
        self._closed = False

    def close(self):
        if self._closed or not self.temporary:
            return
        self._closed = True
        remove(self.email, self.identity_id, keep_audit=self.keep_audit)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def _create(email, role):
    st, ident = staff.call("POST", KRATOS_ADMIN + "/admin/identities", {
        "schema_id": "default", "state": "active", "traits": {"email": email}, "metadata_public": {"role": role}})
    if st not in (200, 201) or "id" not in ident:
        raise SystemExit(f"could not create the Kratos identity ({st}): {json.dumps(ident)[:300]}")
    return ident["id"]


def _enrol(email):
    """Enrol an authenticator and return an aal2 session token.
    Fast path: right after enrolment the session is already aal2 (that is why staff.staff_session's very first call then fails
    with session_already_available), so use it as is. Fallback: call staff.staff_session, and once more if the first call fails
    after the secret file proves the enrolment happened (the second call signs in with the stored secret)."""
    token = staff.email_login(email)
    secret = staff.enrol_totp(token)
    fd = os.open(secret_path(email), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump({"email": email, "secret": secret}, fh)
    st, _ = staff.call("GET", staff.G + "/admin/api/v1/me", headers={"Authorization": "Bearer " + token})
    if st == 200:
        return token
    try:
        return staff.staff_session(email)
    except SystemExit as first:
        print("tempstaff: retrying sign-in (" + str(first)[:60] + ")", file=sys.stderr)
        return staff.staff_session(email)


def open_session(role="ADMIN", keep_audit=False):
    """Creates the temporary account and returns a Handle with an aal2 session token. Cleanup is guaranteed at exit."""
    if role not in ROLES:
        raise ValueError("role must be one of " + ", ".join(ROLES))
    require_dev_stack()
    email = f"{PREFIX}{uuid.uuid4().hex[:8]}@{DOMAIN}"
    handle = Handle(email, None, None)
    handle.keep_audit = keep_audit
    atexit.register(handle.close)
    try:
        handle.identity_id = _create(email, role)
        _psql("registry", f"INSERT INTO staff (identity_id, email, role, status, created_at, updated_at) "
                          f"VALUES ('{handle.identity_id}', '{email}', '{role}', 'ACTIVE', now(), now())")
        handle.token = _enrol(email)
    except BaseException:
        handle.close()
        raise
    return handle


temp_staff = open_session  # `with tempstaff.temp_staff("ADMIN") as handle:`


def remove(email, identity_id=None, keep_audit=False):
    """Deletes everything open_session made for this address. Safe to call twice, and for leftovers."""
    if not (email.startswith(PREFIX) and email.endswith("@" + DOMAIN)):
        raise SystemExit("refusing to remove an account that is not a temporary one: " + email)
    errors = []
    def attempt(label, fn):
        try:
            fn()
        except BaseException as e:  # keep cleaning even if one step fails
            errors.append(f"{label}: {e}")
    if identity_id is None:
        rows = _psql("registry", f"SELECT identity_id FROM staff WHERE lower(email) = '{email.lower()}'")
        identity_id = rows.splitlines()[0] if rows else None
    if identity_id is None:
        st, found = staff.call("GET", KRATOS_ADMIN + "/admin/identities?credentials_identifier=" + urllib.parse.quote(email))
        if st == 200 and isinstance(found, list) and found:
            identity_id = found[0]["id"]
    if identity_id:
        attempt("kratos identity", lambda: staff.call("DELETE", f"{KRATOS_ADMIN}/admin/identities/{identity_id}"))
        if not keep_audit:
            attempt("audit events", lambda: _psql("registry", f"DELETE FROM audit_event WHERE actor_id = '{identity_id}'"))
    attempt("staff row", lambda: _psql("registry", f"DELETE FROM staff WHERE lower(email) = '{email.lower()}'"))
    attempt("secret file", lambda: os.path.exists(secret_path(email)) and os.remove(secret_path(email)))
    recipient_hash = hashlib.sha256(email.strip().lower().encode()).hexdigest()
    attempt("send_log rows", lambda: _psql("notification", f"DELETE FROM send_log WHERE channel = 'EMAIL' AND recipient_hash = '{recipient_hash}'"))
    if errors:
        print("tempstaff: cleanup problems for " + email + ": " + "; ".join(errors), file=sys.stderr)


def clean_leftovers():
    rows = _psql("registry", f"SELECT email FROM staff WHERE email LIKE '{PREFIX}%@{DOMAIN}'")
    emails = rows.splitlines()
    for f in os.listdir(HERE):  # secret files of accounts whose staff row is already gone
        m = re.match(r"\.staff-totp-(" + re.escape(PREFIX.replace("-", "_")) + r"[0-9a-f]{8})_adera_local\.json$", f)
        if m:
            emails.append(m.group(1).replace("_", "-", 1) + "@" + DOMAIN)
    for email in sorted(set(emails)):
        print("removing", email)
        remove(email)
    return len(set(emails))


def script_session(role="ADMIN", keep_audit=False):
    """What the e2e scripts call: a temporary account by default; ADERA_STAFF_EMAIL switches to a real account + typed code."""
    email = os.environ.get("ADERA_STAFF_EMAIL")
    if not email:
        return open_session(role, keep_audit)
    require_dev_stack()
    if os.path.exists(secret_path(email)):
        return Handle(email, None, staff.staff_session(email), temporary=False)
    token = staff.email_login(email)
    code = os.environ.get("ADERA_TOTP") or input("Authenticator app code for " + email + ": ").strip()
    st, f = staff.call("GET", staff.K + "/self-service/login/api?aal=aal2&refresh=false", headers={"X-Session-Token": token})
    if "id" not in f:
        raise SystemExit(f"could not start the authenticator step ({st}): {json.dumps(f)[:300]}")
    st, r = staff.call("POST", f"{staff.K}/self-service/login?flow={f['id']}", {"method": "totp", "totp_code": code}, headers={"X-Session-Token": token})
    if st != 200 or not r.get("session_token"):
        raise SystemExit(f"authenticator code refused ({st}); wait for the next code and retry")
    return Handle(email, None, r["session_token"], temporary=False)


def main(argv):
    if argv[:1] == ["clean"]:
        require_dev_stack()
        print("removed", clean_leftovers(), "leftover temporary account(s)")
        return 0
    if argv[:1] == ["remove"]:
        require_dev_stack()
        remove(argv[1])
        return 0
    if argv[:1] == ["create"]:
        role = argv[argv.index("--role") + 1] if "--role" in argv else "ADMIN"
        h = open_session(role)
        atexit.unregister(h.close)  # it stays until `remove`
        staff.wait_for_next_window()  # the tool that signs in next must not reuse the authenticator code used just now
        print(h.email)
        return 0
    role = "ADMIN"
    if "--role" in argv:
        role = argv[argv.index("--role") + 1]
    with open_session(role) as h:
        st, me = staff.call("GET", staff.G + "/admin/api/v1/me", headers={"Authorization": "Bearer " + h.token})
        print(f"temporary {role} {h.email}: admin API ->", st, me)
        ok = st == 200 and me.get("role") == role
    left = _psql("registry", f"SELECT count(*) FROM staff WHERE email LIKE '{PREFIX}%'")
    st2, _ = staff.call("GET", f"{KRATOS_ADMIN}/admin/identities/{h.identity_id}")
    print("after cleanup: staff rows left =", left, "| kratos identity ->", st2, "| secret file left =", os.path.exists(secret_path(h.email)))
    return 0 if ok and left == "0" and st2 == 404 and not os.path.exists(secret_path(h.email)) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
