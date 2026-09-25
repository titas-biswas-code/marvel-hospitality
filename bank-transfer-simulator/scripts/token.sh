#!/usr/bin/env bash
# Prints a client-credentials access token for the `bank-simulator` service account (contracts/security.md).
# Used by the rest of bank-transfer-simulator to authenticate against bank-transfer-payment-service.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$REPO_ROOT/infra/.env"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
TOKEN_ENDPOINT="$KEYCLOAK_URL/realms/marvel/protocol/openid-connect/token"
CLIENT_ID="bank-simulator"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "error: $ENV_FILE not found. Run 'make up' or 'cp infra/.env.example infra/.env' first." >&2
  exit 1
fi

for bin in curl jq; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "error: '$bin' is required but not found on PATH." >&2
    exit 1
  fi
done

# Same lookup the Makefile `client-token` target uses: grep the KEY=... line, cut the value.
CLIENT_SECRET="$(grep -E '^BANK_SIMULATOR_CLIENT_SECRET=' "$ENV_FILE" | cut -d= -f2-)"

if [[ -z "$CLIENT_SECRET" ]]; then
  echo "error: BANK_SIMULATOR_CLIENT_SECRET not set in $ENV_FILE." >&2
  exit 1
fi

response="$(curl -sf -X POST "$TOKEN_ENDPOINT" \
  -d grant_type=client_credentials \
  -d client_id="$CLIENT_ID" \
  -d client_secret="$CLIENT_SECRET")"

access_token="$(echo "$response" | jq -r '.access_token // empty')"

if [[ -z "$access_token" ]]; then
  echo "error: no access_token in response from $TOKEN_ENDPOINT: $response" >&2
  exit 1
fi

echo "$access_token"
