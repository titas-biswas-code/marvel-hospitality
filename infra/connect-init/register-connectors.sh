#!/usr/bin/env bash
# One-shot connector registration (ADR-0007, contracts/outbox-and-inbox.md). For every infra/debezium/<name>.json:
# PUT /connectors/<name>/config (creates or updates, so re-running is harmless), then wait until the connector and
# its task are RUNNING. Runs in the Debezium Connect image (it already has curl), so no extra image is needed.
# Re-run by hand with: docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm --no-deps connect-init
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://connect:8083}"
CONNECTORS_DIR="${CONNECTORS_DIR:-/connectors}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"

shopt -s nullglob
files=("${CONNECTORS_DIR}"/*.json)
if (( ${#files[@]} == 0 )); then
  echo "error: no connector JSON in ${CONNECTORS_DIR}" >&2
  exit 1
fi

for file in "${files[@]}"; do
  name="$(basename "${file}" .json)"
  code="$(curl -sS -o /tmp/put-response -w '%{http_code}' -X PUT -H 'Content-Type: application/json' \
    --data @"${file}" "${CONNECT_URL}/connectors/${name}/config")"
  if [[ "${code}" != 2* ]]; then
    echo "error: PUT ${name} -> HTTP ${code}: $(cat /tmp/put-response)" >&2
    exit 1
  fi
  echo "registered ${name} (HTTP ${code})"
done

for file in "${files[@]}"; do
  name="$(basename "${file}" .json)"
  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while :; do
    status="$(curl -sS "${CONNECT_URL}/connectors/${name}/status" || true)"
    if grep -q '"state":"FAILED"' <<<"${status}"; then
      echo "error: ${name} FAILED: ${status}" >&2
      exit 1
    fi
    # Connector and task both RUNNING -> two occurrences.
    if (( $(grep -o '"state":"RUNNING"' <<<"${status}" | wc -l) >= 2 )); then
      echo "${name} RUNNING"
      break
    fi
    if (( $(date +%s) > deadline )); then
      echo "error: ${name} not RUNNING after ${TIMEOUT_SECONDS}s: ${status}" >&2
      exit 1
    fi
    sleep 2
  done
done
