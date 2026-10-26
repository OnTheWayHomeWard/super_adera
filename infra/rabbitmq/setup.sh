#!/bin/sh
# Idempotent: creates the vhost, one user per service, and their permissions via the management API.
set -eu
API="http://rabbitmq:15672/api"
AUTH="$RABBITMQ_ADMIN_USER:$RABBITMQ_ADMIN_PASSWORD"

put() { curl -fsS -u "$AUTH" -H 'content-type: application/json' -X PUT "$1" -d "$2" >/dev/null; }

put "$API/vhosts/adera" '{}'

add_user() {
  put "$API/users/$1" "{\"password\":\"$2\",\"tags\":\"\"}"
  # configure / write / read. Wide for now; tighten to per-service name prefixes when the topology exists.
  put "$API/permissions/adera/$1" '{"configure":".*","write":".*","read":".*"}'
  echo "ok: $1"
}

add_user user_svc         "$USER_SVC_RABBIT_PASSWORD"
add_user notification_svc "$NOTIFICATION_SVC_RABBIT_PASSWORD"
echo "rabbitmq setup done"
