#!/usr/bin/env bash
# Headless browser test of the Bridge Lab page in a throw-away Playwright container (the same image the backoffice UI tests use).
# Needs the bridge-lab container running (cd bridge-lab && docker compose up -d). Screenshots go to bridge-lab/tests/out/.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$here/out"
docker run --rm --network host --ipc=host -e BASE="${BASE:-http://localhost:8095}" -e OUT=/work/out \
  -v "$here":/work mcr.microsoft.com/playwright:v1.63.0-noble \
  bash -c 'mkdir -p /tmp/pw && cd /tmp/pw && npm init -y >/dev/null && npm install --no-audit --no-fund playwright@1.63.0 >/dev/null && cp /work/headless.mjs /work/stub-bridge.js /tmp/pw/ && node headless.mjs'
