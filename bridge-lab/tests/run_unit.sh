#!/usr/bin/env bash
# Unit tests of the Bridge Lab logic (front-end modules with a stub bridge, launch-token checks, masking, log book) in node:22-alpine.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
docker run --rm -v "$repo/bridge-lab":/w/bridge-lab:ro -v "$repo/docs/guides":/w/docs/guides:ro -w /w/bridge-lab node:22-alpine node --test tests/unit.test.mjs
