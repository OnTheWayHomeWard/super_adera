#!/usr/bin/env bash
# Browser walk-through of the dashboard against the running dev stack, in a throw-away Playwright container.
# Needs: docker, the stack up in dev mode, the dashboard container running (docker compose up -d backoffice).
# Saves screenshots to tests/ui/out/. Signs in with a TEMPORARY super admin made by tests/e2e/tempstaff.py (random
# tempstaff-xxxx@adera.local, removed again when the run ends, also on failure). It never uses the bootstrap account.
# STAFF_EMAIL=<address> uses an existing account instead (its authenticator secret must be in tests/e2e/.staff-totp-<email>.json).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
mkdir -p "$here/out"
if [ -n "${STAFF_EMAIL:-}" ]; then
  email="$STAFF_EMAIL"
else
  # SUPER_ADMIN because the walkthrough opens the Staff page
  email="$(cd "$repo" && python3 -I tests/e2e/tempstaff.py create --role "${ROLE:-SUPER_ADMIN}" | tail -1)"
  trap '(cd "$repo" && python3 -I tests/e2e/tempstaff.py remove "$email")' EXIT
fi
docker run --rm --network host --ipc=host \
  -e STAFF_EMAIL="$email" -e ROLE="${ROLE:-SUPER_ADMIN}" -e SCRIPT="${SCRIPT:-walkthrough.mjs}" -e GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:9080}" \
  -v "$here":/work -v "$repo/tests/e2e":/e2e:ro \
  mcr.microsoft.com/playwright:v1.63.0-noble \
  bash -c 'mkdir -p /tmp/pw && cd /tmp/pw && npm init -y >/dev/null && npm install --no-audit --no-fund playwright@1.63.0 >/dev/null && cp /work/${SCRIPT:-walkthrough.mjs} /tmp/pw/ && node ${SCRIPT:-walkthrough.mjs}'
