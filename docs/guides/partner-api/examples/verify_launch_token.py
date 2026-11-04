"""Verify an Adera launch token (a mini app backend does this when the super app opens the mini app with ?launch=<token>).
Needs: pip install cryptography. No request signing here: the token is checked against Adera's PUBLIC key from /.well-known/jwks.json.
Usage:   ADERA_BASE=https://api.example ADERA_APP_ID=<your app id> python verify_launch_token.py <token>
Checks (in this order): RS256 with the key named by the header's kid; iss == "adera"; aud == your app id; exp not passed (5 minute life);
env matches the deployment you talk to. On success prints the claims: sub is the user's pseudonymous id (the one for /v1/users/{sub}/profile),
scopes the permissions the user allowed when the app was opened (a HINT only: Adera re-checks consent on every data call)."""
import base64, json, os, sys, time, urllib.request
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

BASE = os.environ["ADERA_BASE"].rstrip("/")
APP_ID = os.environ["ADERA_APP_ID"]
EXPECTED_ENV = os.environ.get("ADERA_ENV", "dev")   # "dev" against the dev deployment, "prod" against production


def b64url(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def adera_keys() -> dict:
    """kid -> public key. Fetch once and cache for an hour in a real service; refetch once when a token carries an unknown kid."""
    with urllib.request.urlopen(BASE + "/.well-known/jwks.json", timeout=10) as r:
        keys = json.load(r)["keys"]
    return {k["kid"]: rsa.RSAPublicNumbers(int.from_bytes(b64url(k["e"]), "big"), int.from_bytes(b64url(k["n"]), "big")).public_key() for k in keys}


def verify(token: str) -> dict:
    header_b64, claims_b64, signature_b64 = token.split(".")
    header = json.loads(b64url(header_b64))
    if header.get("alg") != "RS256":                       # never accept "none" or an HMAC "alg" a caller chooses
        raise ValueError("unexpected alg " + str(header.get("alg")))
    key = adera_keys().get(header.get("kid"))
    if key is None:
        raise ValueError("unknown kid " + str(header.get("kid")))
    key.verify(b64url(signature_b64), (header_b64 + "." + claims_b64).encode("ascii"), padding.PKCS1v15(), hashes.SHA256())  # raises if wrong
    claims = json.loads(b64url(claims_b64))
    if claims["iss"] != "adera":
        raise ValueError("wrong issuer")
    if claims["aud"] != APP_ID:
        raise ValueError("this token is for another app")
    if claims["exp"] < time.time():
        raise ValueError("expired")
    if claims["env"] != EXPECTED_ENV:
        raise ValueError("token of the " + claims["env"] + " environment")
    return claims


if __name__ == "__main__":
    print(json.dumps(verify(sys.argv[1]), indent=2))
