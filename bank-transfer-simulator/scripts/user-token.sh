#!/usr/bin/env bash
# Prints a password-grant access token for a dev user (alice, bob, carol) via the public
# `marvel-postman` client. Used by demo scripts that need to act as a hotel-side user
# (e.g. reading a reservation) rather than as the bank-simulator service account.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$REPO_ROOT/infra/.env"

USERNAME="${1:-${USERNAME:-alice}}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
TOKEN_ENDPOINT="$KEYCLOAK_URL/realms/marvel/protocol/openid-connect/token"
CLIENT_ID="marvel-postman"

for bin in curl jq; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "error: '$bin' is required but not found on PATH." >&2
    exit 1
  fi
done

# infra/.env may not exist yet (e.g. before the first `make up`); fall back to the documented
# default dev password rather than failing, since this isn't a secret worth blocking on.
PASSWORD="password"
if [[ -f "$ENV_FILE" ]]; then
  found="$(grep -E '^DEV_USER_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
  if [[ -n "$found" ]]; then
    PASSWORD="$found"
  fi
fi

response="$(curl -sf -X POST "$TOKEN_ENDPOINT" \
  -d grant_type=password \
  -d client_id="$CLIENT_ID" \
  -d username="$USERNAME" \
  -d password="$PASSWORD")"

access_token="$(echo "$response" | jq -r '.access_token // empty')"

if [[ -z "$access_token" ]]; then
  echo "error: no access_token in response from $TOKEN_ENDPOINT: $response" >&2
  exit 1
fi

echo "$access_token"
