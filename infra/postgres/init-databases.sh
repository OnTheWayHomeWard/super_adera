#!/bin/bash
# Runs once, on first start of an empty data volume.
# One database + one role per service, so no service can read another's data.
set -euo pipefail

create_db() {
  local db="$1" user="$2" pass="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE ${user} LOGIN PASSWORD '${pass}';
CREATE DATABASE ${db} OWNER ${user};
REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
SQL
}

create_db kratos       kratos       "$KRATOS_DB_PASSWORD"
create_db users        users_svc    "$USER_DB_PASSWORD"
create_db notification notif_svc    "$NOTIFICATION_DB_PASSWORD"
create_db infisical    infisical    "$INFISICAL_DB_PASSWORD"
create_db registry     registry_svc "$REGISTRY_DB_PASSWORD"
