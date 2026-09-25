#!/usr/bin/env bash
# Writes the `marvel` realm (incl. users, service accounts and client secrets) to
# realm/marvel-realm.json, the file compose imports with --import-realm. Commit the result.
#
# The dev-file database cannot be opened by two processes, so Keycloak is stopped, a one-off
# container runs `kc.sh export` against the same data volume, and Keycloak is started again.
# Idempotent: re-running overwrites the file with the current realm state.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA="$(dirname "${HERE}")"
ENV_FILE="${INFRA}/.env"
[[ -f "${ENV_FILE}" ]] || ENV_FILE="${INFRA}/.env.example"

compose() { docker compose -f "${INFRA}/docker-compose.yml" --env-file "${ENV_FILE}" "$@"; }
log() { printf '[export] %s\n' "$*"; }

log "stopping keycloak"
compose stop keycloak
# Bring Keycloak back even when the export fails.
trap 'log "starting keycloak"; compose up -d --wait keycloak' EXIT

# `--users realm_file` requires --dir; it writes <dir>/marvel-realm.json with users embedded.
log "exporting realm marvel"
compose run --rm --no-deps -T \
  -v "${HERE}/realm:/opt/keycloak/data/export" \
  keycloak export --realm marvel --users realm_file --dir /opt/keycloak/data/export

log "wrote ${HERE}/realm/marvel-realm.json"
