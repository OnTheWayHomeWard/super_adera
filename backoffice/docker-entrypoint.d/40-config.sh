#!/bin/sh
# Writes /backoffice/config.json from environment variables when the container starts, so one image serves dev and prod.
# Only non-secret settings belong here: the file is public to anyone who can load the dashboard.
set -eu
out=/usr/share/nginx/html/backoffice/config.json

esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
opt() { # opt NAME VALUE -> "name": "value", (skipped when empty)
  if [ -n "$2" ]; then printf '  "%s": "%s",\n' "$1" "$(esc "$2")"; fi
}

hints=false
case "${BACKOFFICE_DEV_HINTS:-false}" in true|TRUE|1|yes) hints=true ;; esac

{
  printf '{\n'
  opt mailpitUrl "${BACKOFFICE_MAILPIT_URL:-}"
  opt grafanaUrl "${BACKOFFICE_GRAFANA_URL:-}"
  opt prometheusUrl "${BACKOFFICE_PROMETHEUS_URL:-}"
  opt infisicalUrl "${BACKOFFICE_INFISICAL_URL:-}"
  opt rabbitmqUrl "${BACKOFFICE_RABBITMQ_URL:-}"
  opt rustfsConsoleUrl "${BACKOFFICE_RUSTFS_CONSOLE_URL:-}"
  printf '  "devHints": %s\n}\n' "$hints"
} > "$out"
