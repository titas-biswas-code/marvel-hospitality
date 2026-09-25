# bank-transfer-simulator

Stands in for "the bank" in local development: scripts and Postman requests that post bank transactions
into `bank-transfer-payment-service`, the same way a real bank's file feed or webhook would. It owns no
service and no database — it is a client of `bank-transfer-payment-service`'s bank-transaction ingestion
endpoint (`docs/contracts/rest-api.md`), authenticated as the `bank-simulator` service account
(`docs/contracts/security.md`).

The transaction-posting scripts and Postman requests are added in PR-04, alongside the outbox/Debezium
wiring they exercise. This PR (PR-01) only adds the shared authentication helper below, since every later
script needs a token.

## `scripts/token.sh`

Prints a client-credentials access token for the `bank-simulator` client to stdout.

```
./scripts/token.sh
```

- Reads the client secret (`BANK_SIMULATOR_CLIENT_SECRET`) from `infra/.env`, resolved relative to the
  script's own location — run it from anywhere, not just the repo root. If `infra/.env` does not exist yet,
  it prints a hint (`make up`, or `cp infra/.env.example infra/.env`) and exits non-zero.
- Requests the token from Keycloak at `KEYCLOAK_URL` (default `http://localhost:8180`, override for a
  non-default setup, e.g. `KEYCLOAK_URL=http://localhost:8180 ./scripts/token.sh`).
- Requires `curl` and `jq` on PATH; fails with a clear message if either is missing or if the token
  response has no `access_token`.

Typical use, once `bank-transfer-payment-service` is up (`make up-apps`):

```
curl -s -X POST http://localhost:8081/bank-transactions \
  -H "Authorization: Bearer $(./bank-transfer-simulator/scripts/token.sh)" \
  -H "Content-Type: application/json" \
  -d @some-transaction.json
```
