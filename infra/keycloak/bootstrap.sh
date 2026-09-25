#!/usr/bin/env bash
# Source of truth for the `marvel` realm (contracts/security.md, ADR-0012).
#
# Idempotent and convergent: every object is created when missing and otherwise updated to the state
# described here, so re-running after an edit applies the edit. Runs kcadm.sh inside the running
# keycloak container; needs docker compose and jq on the host.
#
# After changing this script:  ./bootstrap.sh && ./export.sh   (then commit realm/marvel-realm.json)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA="$(dirname "${HERE}")"
ENV_FILE="${INFRA}/.env"
[[ -f "${ENV_FILE}" ]] || ENV_FILE="${INFRA}/.env.example"
set -a; source "${ENV_FILE}"; set +a

REALM=marvel
ROLES=(reservation:read reservation:write bank:ingest bank:read refund:execute)

compose() { docker compose -f "${INFRA}/docker-compose.yml" --env-file "${ENV_FILE}" "$@"; }
kc() { compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
log() { printf '[bootstrap] %s\n' "$*"; }

# First column of a CSV listing, one value per line (kcadm has no jq inside the container).
ids() { kc get "$@" --format csv --noquotes | tr -d '\r'; }

log "logging in to master realm"
kc config credentials --server http://localhost:8080 --realm master \
  --user "${KEYCLOAK_ADMIN_USERNAME}" --password "${KEYCLOAK_ADMIN_PASSWORD}" > /dev/null

# --- realm -------------------------------------------------------------------------------------
if ids realms --fields realm | grep -qx "${REALM}"; then
  log "realm ${REALM} exists"
else
  log "creating realm ${REALM}"
  kc create realms -s realm="${REALM}" -s enabled=true
fi
# 15-minute access tokens so the README demo does not expire mid-way; no self-registration.
kc update "realms/${REALM}" -s displayName="Marvel Hospitality" -s accessTokenLifespan=900 \
  -s registrationAllowed=false -s loginWithEmailAllowed=true

# Keycloak >= 24 silently drops attributes that are not declared in the user profile.
# `properties` is an unmanaged attribute, so unmanaged attributes must be ENABLED.
log "user profile: unmanagedAttributePolicy=ENABLED"
kc get users/profile -r "${REALM}" \
  | jq '.unmanagedAttributePolicy = "ENABLED"' \
  | kc update users/profile -r "${REALM}" -f - > /dev/null

# --- realm roles -------------------------------------------------------------------------------
existing_roles="$(ids roles -r "${REALM}" --fields name)"
for role in "${ROLES[@]}"; do
  if grep -qx "${role}" <<< "${existing_roles}"; then
    log "role ${role} exists"
  else
    log "creating role ${role}"
    kc create roles -r "${REALM}" -s name="${role}"
  fi
done

# --- client scope carrying the `properties` claim ----------------------------------------------
scope_id() { ids client-scopes -r "${REALM}" --fields id,name | awk -F, -v n="$1" '$2 == n { print $1 }'; }

if [[ -z "$(scope_id properties)" ]]; then
  log "creating client scope properties"
  kc create client-scopes -r "${REALM}" -f - <<'JSON'
{
  "name": "properties",
  "description": "Property ids the principal may operate on (claim: properties)",
  "protocol": "openid-connect",
  "attributes": { "include.in.token.scope": "false", "display.on.consent.screen": "false" }
}
JSON
fi
PROPERTIES_SCOPE_ID="$(scope_id properties)"

MAPPER_JSON='{
  "name": "properties",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-usermodel-attribute-mapper",
  "config": {
    "user.attribute": "properties",
    "claim.name": "properties",
    "jsonType.label": "String",
    "multivalued": "true",
    "access.token.claim": "true",
    "id.token.claim": "false",
    "userinfo.token.claim": "true",
    "introspection.token.claim": "true"
  }
}'
mapper_id="$(ids "client-scopes/${PROPERTIES_SCOPE_ID}/protocol-mappers/models" -r "${REALM}" --fields id,name \
  | awk -F, '$2 == "properties" { print $1 }')"
if [[ -z "${mapper_id}" ]]; then
  log "creating protocol mapper properties (multivalued user attribute -> access token)"
  kc create "client-scopes/${PROPERTIES_SCOPE_ID}/protocol-mappers/models" -r "${REALM}" -f - <<< "${MAPPER_JSON}"
else
  log "updating protocol mapper properties"
  jq --arg id "${mapper_id}" '. + {id: $id}' <<< "${MAPPER_JSON}" \
    | kc update "client-scopes/${PROPERTIES_SCOPE_ID}/protocol-mappers/models/${mapper_id}" -r "${REALM}" -f -
fi
# attach_scope <collection path>: adds the properties scope unless present (a second PUT answers 409).
attach_scope() {
  if ! ids "$1" -r "${REALM}" --fields id | grep -qx "${PROPERTIES_SCOPE_ID}"; then
    kc update "$1/${PROPERTIES_SCOPE_ID}" -r "${REALM}"
  fi
}
# Default scope for every client created from now on; existing clients are attached explicitly below.
attach_scope "realms/${REALM}/default-default-client-scopes"

# --- clients -----------------------------------------------------------------------------------
client_id_of() { ids clients -r "${REALM}" -q clientId="$1" --fields id,clientId | awk -F, -v c="$1" '$2 == c { print $1 }'; }

# upsert_client <clientId> <json>
upsert_client() {
  local client="$1" json="$2" id
  id="$(client_id_of "${client}")"
  if [[ -z "${id}" ]]; then
    log "creating client ${client}"
    kc create clients -r "${REALM}" -f - <<< "${json}"
    id="$(client_id_of "${client}")"
  else
    log "updating client ${client}"
    kc update "clients/${id}" -r "${REALM}" -f - <<< "${json}"
  fi
  attach_scope "clients/${id}/default-client-scopes"
}

# service_account_client <clientId> <secret> <description>
service_account_client() {
  jq -n --arg c "$1" --arg s "$2" --arg d "$3" '{
    clientId: $c, name: $c, description: $d, enabled: true, protocol: "openid-connect",
    publicClient: false, clientAuthenticatorType: "client-secret", secret: $s,
    serviceAccountsEnabled: true, standardFlowEnabled: false, implicitFlowEnabled: false,
    directAccessGrantsEnabled: false
  }'
}

upsert_client marvel-postman "$(jq -n '{
  clientId: "marvel-postman", name: "marvel-postman",
  description: "Humans and Postman. Password grant is for local development only.",
  enabled: true, protocol: "openid-connect", publicClient: true,
  standardFlowEnabled: true, directAccessGrantsEnabled: true, implicitFlowEnabled: false,
  redirectUris: ["http://localhost:*", "https://oauth.pstmn.io/v1/callback"], webOrigins: ["+"],
  attributes: { "pkce.code.challenge.method": "S256" }
}')"
upsert_client bank-simulator "$(service_account_client bank-simulator "${BANK_SIMULATOR_CLIENT_SECRET}" \
  'Bank simulator posting transactions to bank-transfer-payment-service')"
upsert_client room-reservation-service "$(service_account_client room-reservation-service "${ROOM_RESERVATION_SERVICE_CLIENT_SECRET}" \
  'Reserved for future service-to-service calls (ADR-0012)')"
upsert_client bank-transfer-payment-service "$(service_account_client bank-transfer-payment-service "${BANK_TRANSFER_PAYMENT_SERVICE_CLIENT_SECRET}" \
  'Reserved for future service-to-service calls (ADR-0012)')"

# --- users -------------------------------------------------------------------------------------
user_id_of() { ids users -r "${REALM}" -q username="$1" -q exact=true --fields id,username | awk -F, -v u="$1" '$2 == u { print $1 }'; }

# set_properties <userId> <json array>  (merges into existing attributes)
# Sends the full user back: with the declarative user profile, profile fields missing from an update
# (email, firstName, lastName) are cleared, which then fails the password grant.
set_properties() {
  kc get "users/$1" -r "${REALM}" \
    | jq --argjson p "$2" '.attributes = ((.attributes // {}) + {properties: $p})' \
    | kc update "users/$1" -r "${REALM}" -f -
}

# grant_roles <username> <role>...  (adding an already-granted role is a no-op)
grant_roles() {
  local user="$1"; shift
  local args=()
  for role in "$@"; do args+=(--rolename "${role}"); done
  kc add-roles -r "${REALM}" --uusername "${user}" "${args[@]}"
}

# human_user <username> <first> <last> <properties json> <role>...
human_user() {
  local user="$1" first="$2" last="$3" props="$4"; shift 4
  local id
  id="$(user_id_of "${user}")"
  if [[ -z "${id}" ]]; then
    log "creating user ${user}"
    kc create users -r "${REALM}" -s username="${user}" -s enabled=true
    id="$(user_id_of "${user}")"
    # Only on creation: re-setting it every run re-salts the hash and churns the exported JSON.
    kc set-password -r "${REALM}" --userid "${id}" --new-password "${DEV_USER_PASSWORD}"
  else
    log "updating user ${user}"
  fi
  # Email/first/last are required by the default user profile; without them the password grant fails
  # with "Account is not fully set up".
  kc update "users/${id}" -r "${REALM}" -s firstName="${first}" -s lastName="${last}" \
    -s email="${user}@marvel.local" -s emailVerified=true -s enabled=true -s 'requiredActions=[]'
  set_properties "${id}" "${props}"
  grant_roles "${user}" "$@"
}

human_user alice Alice Anderson '["AMS01","RTM01"]' reservation:read reservation:write
human_user bob   Bob   Brown    '["AMS01"]'         reservation:read reservation:write
human_user carol Carol Clark    '["RTM01"]'         reservation:read

# Service accounts operate on every property.
for client in bank-simulator room-reservation-service bank-transfer-payment-service; do
  log "service account ${client}: properties=[\"*\"]"
  sa_id="$(kc get "clients/$(client_id_of "${client}")/service-account-user" -r "${REALM}" --fields id --format csv --noquotes | tr -d '\r')"
  set_properties "${sa_id}" '["*"]'
done
grant_roles service-account-bank-simulator bank:ingest

log "done. Run ./export.sh to refresh realm/marvel-realm.json."
