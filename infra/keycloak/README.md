# infra/keycloak

The `marvel` realm: how it's built, how to change it, and how to get tokens out of it. Contract:
`docs/contracts/security.md`.

## How the realm is produced

`bootstrap.sh` is the **source of truth**, not the committed JSON. It is idempotent and convergent: every
run either creates each object (if missing) or updates it to the state described in the script, so
re-running after an edit applies that edit and re-running with no edit is a no-op. It works by driving
`kcadm.sh` inside the running `keycloak` container. It creates:

- the `marvel` realm (15-minute access tokens, no self-registration);
- the realm's user profile with `unmanagedAttributePolicy: ENABLED` — Keycloak ≥24 silently drops any user
  attribute not declared in the profile, and `properties` is not a declared attribute, so this flag is
  required or the claim below is silently empty;
- 5 realm roles: `reservation:read`, `reservation:write`, `bank:ingest`, `bank:read`, `refund:execute`;
- a `properties` client scope carrying a multivalued **User Attribute** protocol mapper (`properties` user
  attribute → `properties` claim, added to the access token), set as a realm-default client scope and
  explicitly attached to every client created below;
- 4 clients (`marvel-postman`, `bank-simulator`, `room-reservation-service`, `bank-transfer-payment-service`);
- 3 human users (`alice`, `bob`, `carol`);
- service accounts for the 3 confidential clients, each with `properties=["*"]` (all properties); the
  `bank-simulator` service account is additionally granted `bank:ingest`.

`export.sh` turns the running realm into the committed file. Keycloak's dev database (`dev-file`) cannot be
opened by two processes at once, so the script stops the `keycloak` container, runs
`kc.sh export --realm marvel --users realm_file --dir <path>` in a one-off container against the same data
volume (`--users realm_file` requires `--dir`; it cannot be combined with a single `--file` target), writes
`infra/keycloak/realm/marvel-realm.json`, then restarts `keycloak`.

**Never hand-edit `realm/marvel-realm.json`.** It is a generated artifact; edit `bootstrap.sh` instead.

### Workflow to change the realm

```
make up                             # keycloak must be running
vim infra/keycloak/bootstrap.sh     # edit the realm definition
./infra/keycloak/bootstrap.sh       # apply the change to the running keycloak
./infra/keycloak/export.sh          # regenerate realm/marvel-realm.json
make reset                          # prove the file imports cleanly into an empty keycloak-data volume
git add infra/keycloak/realm/marvel-realm.json infra/keycloak/bootstrap.sh
git commit ...
```

`make reset` is not optional in this flow: `--import-realm` only imports on an empty Keycloak database, so
it's the only way to verify the exported file is actually valid and complete before committing it.

## Users

Password `password` for all three (dev only; `DEV_USER_PASSWORD` in `infra/.env`).

| user | roles | properties |
|---|---|---|
| `alice` | `reservation:read`, `reservation:write` | `AMS01`, `RTM01` |
| `bob` | `reservation:read`, `reservation:write` | `AMS01` |
| `carol` | `reservation:read` | `RTM01` |

## Clients

Secrets come from `infra/.env` (`BANK_SIMULATOR_CLIENT_SECRET`, `ROOM_RESERVATION_SERVICE_CLIENT_SECRET`,
`BANK_TRANSFER_PAYMENT_SERVICE_CLIENT_SECRET`), copied by `bootstrap.sh` into the realm.

| clientId | type | grants | purpose |
|---|---|---|---|
| `marvel-postman` | public | password (dev only), authorization code | humans / Postman |
| `bank-simulator` | confidential, service account | client credentials | simulator → payment service; role `bank:ingest` |
| `room-reservation-service` | confidential, service account | client credentials | reserved for future service-to-service calls (ADR-0012) |
| `bank-transfer-payment-service` | confidential, service account | client credentials | reserved for future service-to-service calls (ADR-0012) |

## Getting tokens

Password grant for a dev user (the contract one-liner, also the Postman pre-request script):

```
curl -s -X POST http://localhost:8180/realms/marvel/protocol/openid-connect/token \
  -d grant_type=password -d client_id=marvel-postman -d username=alice -d password=password \
  | jq -r .access_token
```

Or via the root Makefile:

```
make token USER=bob
```

Client-credentials grant for a service account:

```
curl -s -X POST http://localhost:8180/realms/marvel/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=bank-simulator -d client_secret=bank-simulator-dev-secret \
  | jq -r .access_token
```

Or:

```
make client-token CLIENT=bank-simulator
```

Decode a token (no signature check, just inspection):

```
echo "$TOKEN" | jq -R 'split(".")[1] | @base64d | fromjson'
```

Access tokens live 15 minutes (`accessTokenLifespan=900`, set in `bootstrap.sh`).

## Admin console

http://localhost:8180/admin — login `admin` / `admin` (`KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD`
in `infra/.env`).

## Troubleshooting

- **"Account is not fully set up"** on a password-grant request: the default Keycloak user profile requires
  `email`, `firstName`, `lastName`, and the grant fails if any are missing. `bootstrap.sh` sets all three for
  every human user — if you see this error after hand-editing a user in the admin console, re-run
  `bootstrap.sh` to restore them.
