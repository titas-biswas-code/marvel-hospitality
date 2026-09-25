# Security contract

- IdP: Keycloak 26.x, realm `marvel`. Source of truth is `infra/keycloak/bootstrap.sh` (idempotent `kcadm.sh` script);
  `infra/keycloak/export.sh` produces the committed `infra/keycloak/realm/marvel-realm.json`, which compose imports with
  `--import-realm` (import runs only on an empty Keycloak DB — `make reset` wipes it).
- Realm user-profile must have `unmanagedAttributePolicy: ENABLED` or the `properties` user attribute is dropped.
- Issuer: `http://localhost:8180/realms/marvel` for tokens obtained from the host. Services run inside compose and set
  `issuer-uri` to that value **and** `jwk-set-uri` to `http://keycloak:8080/realms/marvel/protocol/openid-connect/certs`
  (Keycloak `KC_HOSTNAME=http://localhost:8180`, `KC_HOSTNAME_STRICT=false`, `KC_HTTP_ENABLED=true`).
- Every service is an OAuth2 **resource server** (`spring.security.oauth2.resourceserver.jwt.issuer-uri`).
  No gateway in this repo (ADR-0012).
- Realm roles (mapped into `realm_access.roles`, converted by a custom converter into authorities with their raw
  names, no `ROLE_` prefix; endpoints check them with `hasAuthority(...)`):
  `reservation:read`, `reservation:write`, `bank:ingest`, `bank:read`, `refund:execute`.
- Custom claim `properties`: JSON array of propertyIds, from user attribute `properties` via a
  "User Attribute" protocol mapper (multivalued). Services enforce `propertyId ∈ properties` with a
  `@PreAuthorize("@propertyAccess.allowed(#propertyId)")` style check → `403 FORBIDDEN_PROPERTY`.
  Service accounts get `properties = ["*"]` meaning all.
- Clients:
  | clientId | type | grants | purpose |
  |---|---|---|---|
  | `marvel-postman` | public | password (direct access grants, dev only), authorization code | humans / Postman |
  | `bank-simulator` | confidential, service account | client credentials | simulator → payment service; roles `bank:ingest` |
  | `room-reservation-service` | confidential, service account | client credentials | reserved for future S2S calls (ADR-0012) |
  | `bank-transfer-payment-service` | confidential, service account | client credentials | idem |
- Users (dev only, password `password`):
  | user | roles | properties |
  |---|---|---|
  | `alice` | reservation:read, reservation:write | AMS01, RTM01 |
  | `bob` | reservation:read, reservation:write | AMS01 |
  | `carol` | reservation:read | RTM01 |
- Token one-liner (README + Postman pre-request script):
  `curl -s -X POST http://localhost:8180/realms/marvel/protocol/openid-connect/token -d grant_type=password -d client_id=marvel-postman -d username=alice -d password=password | jq -r .access_token`
- Tests: `spring-security-test` `jwt()` request post-processor with `claim("properties", List.of("AMS01"))`
  and authorities; no Keycloak container except one smoke test in PR-01.
- Public endpoints: `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/reference-data`.
