"""App-user (super app) sign-in through the GATEWAY, as a module the e2e scripts import (journey.py, seed_scenarios.py).

Users sign in with a PASSWORD (phone or email + password). A new account cannot sign in until its phone/email is verified with a
code that arrives by SMS/email (dev mode: read from notification-service's /dev/messages). Forgot password: by email through
Kratos' recovery flow, by phone through our password-reset endpoints (Kratos' recovery flow only accepts an email address).

Everything here goes through http://127.0.0.1:9080 (the gateway), paced to stay under its per-IP limit on flow submits."""
import collections, json, os, re, sys, time, urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import staff

G, K, N = staff.G, staff.K, staff.N


class Pace:
    """The gateway allows a limited number of Kratos flow submits per minute per IP; wait instead of failing with 429."""

    def __init__(self, limit, window=61):
        self.limit, self.window, self.stamps = limit, window, collections.deque()

    def wait(self, n=1):
        while True:
            now = time.time()
            while self.stamps and now - self.stamps[0] > self.window:
                self.stamps.popleft()
            if len(self.stamps) + n <= self.limit:
                break
            time.sleep(max(1.0, self.window - (now - self.stamps[0])))
        for _ in range(n):
            self.stamps.append(time.time())


# login submits and registration/verification/recovery/settings submits have their own gateway limits (see apisix.yaml)
PACE = {"login": Pace(8), "other": Pace(8)}


def submit(kind, flow_id, body, headers=None):
    """POST one step of a native flow. kind: login | registration | verification | recovery | settings."""
    PACE["login" if kind == "login" else "other"].wait()
    for _ in range(3):
        st, r = staff.call("POST", f"{K}/self-service/{kind}?flow={flow_id}", body, headers)
        if st != 429:
            return st, r
        time.sleep(30)
    return st, r


def init(kind, headers=None):
    st, f = staff.call("GET", f"{K}/self-service/{kind}/api", headers=headers)
    if "id" not in f:
        raise SystemExit(f"could not start a {kind} flow ({st}): {json.dumps(f)[:300]}")
    return f["id"]


def messages(to):
    st, m = staff.call("GET", f"{N}/dev/messages?to={urllib.parse.quote(to)}")
    return m if isinstance(m, list) else []


def wait_code(to, after=None, tries=25):
    """The newest 6-digit code sent to this address (dev endpoint), newer than `after` (a previous message timestamp)."""
    for _ in range(tries):
        for m in messages(to):
            if after is None or m["at"] > after:
                hit = re.search(r"\b(\d{6})\b", (m.get("subject") or "") + " " + (m.get("body") or ""))
                if hit:
                    return hit.group(1), m["at"]
        time.sleep(1)
    raise SystemExit("no code arrived for " + to)


def last_at(to):
    m = messages(to)
    return m[0]["at"] if m else None


def register(password, phone=None, email=None):
    """Registration step. Returns (status, response); a 200 carries the identity and continue_with -> verification flow."""
    traits = {}
    if phone:
        traits["traits.phone_number"] = phone
    if email:
        traits["traits.email"] = email
    return submit("registration", init("registration"), {"method": "password", "password": password, **traits})


def verify(flow_id, code):
    return submit("verification", flow_id, {"method": "code", "code": code})


def login(identifier, password):
    return submit("login", init("login"), {"method": "password", "identifier": identifier, "password": password})


def signup_verified(password, phone=None, email=None):
    """Register, verify the first address with the code from the dev endpoint, sign in. Returns (session_token, identity_id)."""
    ident = phone or email
    mark = last_at(ident)
    st, r = register(password, phone, email)
    if st != 200:
        raise SystemExit(f"registration of {ident} failed ({st}): {json.dumps(r)[:300]}")
    flow = r["continue_with"][0]["flow"]["id"]
    code, _ = wait_code(ident, mark)
    st, v = verify(flow, code)
    if v.get("state") != "passed_challenge":
        raise SystemExit(f"verification of {ident} failed ({st}): {json.dumps(v)[:300]}")
    st, s = login(ident, password)
    if not s.get("session_token"):
        raise SystemExit(f"login of {ident} failed ({st}): {json.dumps(s)[:300]}")
    return s["session_token"], r["identity"]["id"]
