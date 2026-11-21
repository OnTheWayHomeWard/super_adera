#!/usr/bin/env bash
# Runs ON the staging server (called by the deploy job in .github/workflows/ci.yml, or by hand as the deploy user):
#   /opt/adera/deploy/staging/deploy.sh [git-ref]
# Brings /opt/adera to the staging branch, pulls the images CI just pushed, restarts what changed and checks health.
# The data (Docker volumes) is never touched.
set -euo pipefail
# Everything is inside main so bash has read the whole script before git replaces this file.
main() {
cd /opt/adera
REF="${1:-origin/staging}"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.staging.yml)

git fetch --quiet origin
git reset --quiet --hard "$REF"        # ignored files (htpasswd, CREDENTIALS.md) are kept
echo "at $(git log --oneline -1)"

"${COMPOSE[@]}" pull --quiet notification-service user-service registry-service backoffice edge bridge-lab caddy
"${COMPOSE[@]}" up -d --remove-orphans
# APISIX caches the containers' addresses; recreated services get new ones.
"${COMPOSE[@]}" restart apisix

check() {   # name url
  for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$2"; then echo "ok   $1"; return 0; fi
    sleep 5
  done
  echo "FAIL $1 ($2)"; return 1
}
fail=0
check notification-service http://127.0.0.1:8085/actuator/health || fail=1
check user-service         http://127.0.0.1:8086/actuator/health || fail=1
check registry-service     http://127.0.0.1:8087/actuator/health || fail=1
check gateway+backoffice   http://127.0.0.1:9080/backoffice/     || fail=1
check docs                 "http://${STAGING_PUBLIC_IP:-157.245.133.250}:8090/" || fail=1
"${COMPOSE[@]}" ps --format '{{.Service}}\t{{.Status}}'
docker image prune -f >/dev/null
return $fail
}
main "$@"
exit $?
