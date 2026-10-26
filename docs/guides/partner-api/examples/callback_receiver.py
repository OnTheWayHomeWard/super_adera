"""A callback endpoint: verifies Adera's signature, de-duplicates on Idempotency-Key, answers 2xx.
Run: ADERA_BASE=... ADERA_KEY_ID=x ADERA_PRIVATE_KEY_FILE=... python callback_receiver.py 8099"""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from adera_client import verify_callback

seen = set()          # use a database or cache with expiry in production


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            verify_callback(self.path, raw, self.headers)       # self.path = path + query exactly as received
        except Exception as e:
            print("REJECTED:", e); self.send_response(401); self.end_headers(); return
        key = self.headers["Idempotency-Key"]
        if key in seen:
            print("duplicate delivery ignored", key)
        else:
            seen.add(key)
            event = json.loads(raw)
            print("callback", self.headers["X-Adera-Event"], json.dumps(event))
        self.send_response(204); self.end_headers()          # any 2xx means "received"

    def log_message(self, *a):
        pass


HTTPServer(("0.0.0.0", int(sys.argv[1])), Hook).serve_forever()
