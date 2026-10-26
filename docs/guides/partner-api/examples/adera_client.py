"""Minimal Adera partner API client. Needs: pip install cryptography requests
Environment: ADERA_BASE (e.g. https://api.example), ADERA_KEY_ID, ADERA_PRIVATE_KEY_FILE (PEM, your PRIVATE key)."""
import base64, hashlib, json, os, time
import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

BASE = os.environ["ADERA_BASE"].rstrip("/")
KEY_ID = os.environ["ADERA_KEY_ID"]
with open(os.environ["ADERA_PRIVATE_KEY_FILE"], "rb") as f:
    PRIVATE_KEY = serialization.load_pem_private_key(f.read(), password=None)


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def sha256_b64(data: bytes) -> str:
    return b64(hashlib.sha256(data).digest())


def rs256_sign(canonical: str) -> str:
    return b64(PRIVATE_KEY.sign(canonical.encode(), padding.PKCS1v15(), hashes.SHA256()))


def call(method: str, path: str, body: dict | None = None):
    """Send one signed request. `path` includes the query string exactly as it goes on the wire."""
    raw = json.dumps(body, separators=(",", ":")).encode() if body is not None else b""
    ts = str(int(time.time()))
    canonical = "\n".join(["ADERA-SIG-1", ts, method.upper(), path, sha256_b64(raw)])
    signature = rs256_sign(canonical)
    response = requests.request(
        method, BASE + path, data=raw if raw else None, timeout=15,
        headers={"X-Adera-Key-Id": KEY_ID, "X-Adera-Timestamp": ts, "X-Adera-Signature": signature,
                 "Content-Type": "application/json"})
    verify_response(response, signature)
    return response


# ---- verifying what Adera sends back (responses AND callbacks) ---------------------------------------------------

_jwks_cache: dict = {}


def adera_public_key(kid: str):
    """Adera's public key from /.well-known/jwks.json, cached for an hour (refetch once if the kid is unknown)."""
    entry = _jwks_cache.get(kid)
    if entry is None or time.time() - entry[1] > 3600:
        keys = requests.get(BASE + "/.well-known/jwks.json", timeout=10).json()["keys"]
        for k in keys:
            n = int.from_bytes(base64.urlsafe_b64decode(k["n"] + "=" * (-len(k["n"]) % 4)), "big")
            e = int.from_bytes(base64.urlsafe_b64decode(k["e"] + "=" * (-len(k["e"]) % 4)), "big")
            _jwks_cache[k["kid"]] = (rsa.RSAPublicNumbers(e, n).public_key(), time.time())
        entry = _jwks_cache.get(kid)
    if entry is None:
        raise ValueError("unknown Adera key id " + kid)
    return entry[0]


def _check(canonical: str, headers) -> None:
    ts = headers["X-Adera-Timestamp"]
    if abs(time.time() - int(ts)) > 300:
        raise ValueError("stale timestamp")
    key = adera_public_key(headers["X-Adera-Key-Id"])
    key.verify(base64.b64decode(headers["X-Adera-Signature"]), canonical.encode(), padding.PKCS1v15(), hashes.SHA256())  # raises if wrong


def verify_response(response: requests.Response, request_signature: str) -> None:
    if "X-Adera-Signature" not in response.headers:   # gateway answers (429, 502..504) and framework 404/405/415 are unsigned
        raise RuntimeError(f"unsigned answer, HTTP {response.status_code}: not produced by the API itself")
    ts = response.headers["X-Adera-Timestamp"]
    canonical = "\n".join(["ADERA-SIG-1-RESPONSE", ts, str(response.status_code), sha256_b64(response.content),
                           sha256_b64(request_signature.encode())])
    _check(canonical, response.headers)


def verify_callback(path_and_query: str, raw_body: bytes, headers) -> None:
    """Call this in your callback endpoint with the raw (unparsed) body and the request headers."""
    canonical = "\n".join(["ADERA-SIG-1", headers["X-Adera-Timestamp"], "POST", path_and_query, sha256_b64(raw_body)])
    _check(canonical, headers)
