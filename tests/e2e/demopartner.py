"""Signed partner calls for the SEEDED demo apps (demo-food, demo-ride, ...), through the gateway, for the e2e journeys and for
capturing documentation samples. Needs tests/e2e/.seed-scenarios-state.json and .seed-keys/ (made by seed_scenarios.py; both gitignored).
DEV only. Uses openssl for RS256 (only the partner's PRIVATE key signs; we never see it elsewhere)."""
import base64, hashlib, json, os, subprocess, sys, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import staff

G = staff.G
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
STATE = json.load(open(os.path.join(HERE, ".seed-scenarios-state.json")))
KEYS = os.path.join(HERE, ".seed-keys")


def env_value(name):
    for line in open(os.path.join(ROOT, ".env")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()


def app_id(slug):
    return STATE["apps"][slug]["id"]


def sign(slug, canonical, env="dev"):
    priv = os.path.join(KEYS, f"{slug}.{env}.pem")
    return base64.b64encode(subprocess.run(["openssl", "dgst", "-sha256", "-sign", priv], input=canonical.encode(), capture_output=True, check=True).stdout).decode()


def pcall(slug, method, path, body=None, env="dev", ts=None):
    """-> (status, json body, headers). The request is signed over method, path (with query) and the body hash (ADERA-SIG-1)."""
    data = json.dumps(body).encode() if body is not None else b""
    ts = str(ts or int(time.time()))
    canonical = f"ADERA-SIG-1\n{ts}\n{method}\n{path}\n{base64.b64encode(hashlib.sha256(data).digest()).decode()}"
    headers = {"X-Adera-Key-Id": STATE["keys"][f"{slug}:{env}"], "X-Adera-Timestamp": ts, "X-Adera-Signature": sign(slug, canonical, env), "Content-Type": "application/json"}
    for attempt in range(3):
        try:
            r = urllib.request.urlopen(urllib.request.Request(G + path, data=data or None, method=method, headers=headers), timeout=30)
            code, raw, hdrs = r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            code, raw, hdrs = e.code, e.read(), e.headers
        if code == 429 and json.loads(raw or b"{}").get("error") not in ("quota_exceeded",) and attempt < 2:
            time.sleep(20)
            continue
        break
    try:
        return code, json.loads(raw or b"{}"), hdrs
    except ValueError:
        return code, {"raw": raw[:100].decode(errors="replace")}, hdrs


def mint_alias(slug, user_id):
    """The user's pseudonymous id for the app (the launch endpoint will do this for real users; until then the internal call)."""
    st, r = staff.call("POST", "http://127.0.0.1:8086/internal/aliases", {"appId": app_id(slug), "userId": user_id}, {"X-Internal-Secret": env_value("INTERNAL_API_SECRET")})
    return r.get("alias")


def psql(db, sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "adera_admin", "-d", db, "-Atc", sql], capture_output=True, text=True, cwd=ROOT).stdout.strip()
