#!/usr/bin/env bash
# Signed call to the Adera partner API with only curl and openssl.
# usage: adera_call.sh METHOD PATH [BODY_FILE]      e.g.  adera_call.sh POST /v1/notifications body.json
# env:   ADERA_BASE, ADERA_KEY_ID, ADERA_PRIVATE_KEY_FILE
set -euo pipefail
method=$(printf '%s' "$1" | tr a-z A-Z); path=$2; body_file=${3:-}

b64() { base64 | tr -d '\n'; }                       # one line, standard alphabet, with padding
ts=$(date +%s)
if [ -n "$body_file" ]; then body_hash=$(openssl dgst -sha256 -binary "$body_file" | b64)
else                          body_hash=$(openssl dgst -sha256 -binary </dev/null | b64); fi   # empty body = hash of zero bytes

# five lines joined by \n, no trailing newline; the body hash covers the exact bytes of BODY_FILE
canonical=$(printf 'ADERA-SIG-1\n%s\n%s\n%s\n%s' "$ts" "$method" "$path" "$body_hash")
signature=$(printf '%s' "$canonical" | openssl dgst -sha256 -sign "$ADERA_PRIVATE_KEY_FILE" | b64)   # RSASSA-PKCS1-v1_5 + SHA-256

args=(-sS -i -X "$method" "$ADERA_BASE$path"
      -H "X-Adera-Key-Id: $ADERA_KEY_ID" -H "X-Adera-Timestamp: $ts" -H "X-Adera-Signature: $signature"
      -H "Content-Type: application/json")
[ -n "$body_file" ] && args+=(--data-binary "@$body_file")      # --data-binary: curl must not alter a single byte
curl "${args[@]}"
