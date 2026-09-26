# bank-transfer-simulator

Stands in for "the bank" in local development: scripts and Postman requests that post bank transactions
into `bank-transfer-payment-service`, the same way a real bank's file feed or webhook would. It owns no
service and no database — it is a client of `bank-transfer-payment-service`'s bank-transaction ingestion
endpoint (`docs/contracts/rest-api.md`), authenticated as the `bank-simulator` service account
(`docs/contracts/security.md`).

All scripts assume `bank-transfer-payment-service` (and, for `pay-in-full.sh`, `room-reservation-service`)
are up: `make up-apps`.

## `scripts/token.sh`

Prints a client-credentials access token for the `bank-simulator` client (role `bank:ingest`) to stdout.

```
./scripts/token.sh
```

- Reads the client secret (`BANK_SIMULATOR_CLIENT_SECRET`) from `infra/.env`, resolved relative to the
  script's own location — run it from anywhere, not just the repo root. If `infra/.env` does not exist yet,
  it prints a hint (`make up`, or `cp infra/.env.example infra/.env`) and exits non-zero.
- Requests the token from Keycloak at `KEYCLOAK_URL` (default `http://localhost:8180`).
- Requires `curl` and `jq` on PATH; fails with a clear message if either is missing or if the token
  response has no `access_token`.

## `scripts/user-token.sh`

Prints a password-grant access token for a dev user (`alice`, `bob`, `carol`) via the public
`marvel-postman` client. Used by `pay-in-full.sh` to read a reservation as a hotel-side user, since the
bank-simulator credential has no `reservation:read`.

```
./scripts/user-token.sh alice
USERNAME=bob ./scripts/user-token.sh
```

- Password is `DEV_USER_PASSWORD` from `infra/.env` (falls back to the documented default `password` if
  `infra/.env` is missing).
- `KEYCLOAK_URL` override same as `token.sh`.

## `scripts/post-bank-transaction.sh`

Posts one bank transaction to `POST /bank-transactions`.

```
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 120
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 120 --ref BANK-TX-1
./scripts/post-bank-transaction.sh --description "garbage" --amount 50
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 50 --currency USD
./scripts/post-bank-transaction.sh --help
```

- `--reservation <id>` or `--description <text>` is required (the latter overrides the former and is
  used verbatim as `remittanceInformation`); `--amount <amount>` is always required.
- Amount is normalised to exactly 2 decimals using string arithmetic only, never floating point
  (`120` → `120.00`, `120.5` → `120.50`); more than 2 decimal places or a non-numeric value is rejected
  before anything is sent.
- Defaults: `--ref` `BANK-TX-<UTC yyyymmddHHMMSS>-<4 random digits>`, `--e2e` 10 random digits, `--debtor`
  `NL91ABNA0417164300`, `--debtor-name` `A. Lovelace`, `--currency` `EUR`, `--booked-at` now (UTC).
- Without `--description`, the remittance information sent is `<e2e> <reservation>`, e.g.
  `1401541457 P4145478` — the format `bank-transfer-payment-service` parses to match a reservation.
- Gets its token from `token.sh`; `PAYMENT_URL` env var overrides the default `http://localhost:8081`.
- Prints the HTTP status and the (pretty-printed, if JSON) response body; exits non-zero on 4xx/5xx.

## `scripts/pay-in-full.sh`

```
./scripts/pay-in-full.sh --property AMS01 --reservation P4145478
```

Reads the reservation (`GET /properties/{propertyId}/reservations/{reservationId}`, as dev user `alice`
by default, via `user-token.sh`), computes `outstanding = totalAmount - amountReceived` using integer-cents
arithmetic (never floating point), refuses if the reservation is not `PENDING_PAYMENT` or outstanding is
`<= 0`, then calls `post-bank-transaction.sh --reservation ... --amount <outstanding>`. Any extra flag
(e.g. `--ref`) is passed through to `post-bank-transaction.sh`. `RESERVATION_URL` env var overrides the
default `http://localhost:8080`.

This is a **demo convenience only**. A real bank never knows a reservation's total or already-received
amount — it only ever sees what the debtor instructed it to transfer. This script exists so a manual demo
doesn't require reading the outstanding amount off Swagger and typing it into `post-bank-transaction.sh`
by hand.

## Happy path: create a reservation, pay it, see the Kafka message

1. Get a token and create a `BANK_TRANSFER` reservation (Postman "Reservations → Create bank-transfer
   reservation", or the reservation service's Swagger UI). Note the `reservationId`, e.g. `P4145478`.
2. `./scripts/pay-in-full.sh --property AMS01 --reservation P4145478`
3. Open kafka-ui (http://localhost:8090), topic `bank-transfer-payment-update`. A new message appears
   with key = the payment's `paymentId` and headers `id`, `eventType`, `eventVersion`, `producer`,
   `occurredAt`, `traceparent` (`propertyId` header is null on this topic — it isn't property-scoped
   money yet). The value is `{"paymentId", "debtorAccountnumber", "amountReceived", "transactionDescription"}`.
4. Until the reservation service consumes this topic (PR-05), the reservation stays `PENDING_PAYMENT` —
   the message on Kafka is the payment side of the saga done; the reservation side is next.

## Duplicate transaction (idempotency)

```
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 120 --ref BANK-TX-DEMO-1
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 120 --ref BANK-TX-DEMO-1
```

First call → `202` with a new `paymentId` and a Kafka message. Second call with the same `--ref` → `200`
with the *same* `paymentId` and no second Kafka message (check kafka-ui's message count on the topic
before and after).

## Partial payment

```
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 120 --ref BANK-TX-DEMO-PARTIAL-1
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 120 --ref BANK-TX-DEMO-PARTIAL-2
```

Two payments of 120 each on a 240 reservation: two `202`s, two distinct `paymentId`s, two Kafka messages.

## Unmatched remittance format

```
./scripts/post-bank-transaction.sh --description "garbage" --amount 50
```

Still `202 Accepted` from `bank-transfer-payment-service` — ingestion and publishing don't parse the
remittance information, matching to a reservation is the consumer's job (PR-05). Once that consumer
exists, this is the case that produces an unmatched payment for reconciliation.

## Non-EUR currency

```
./scripts/post-bank-transaction.sh --reservation P4145478 --amount 50 --currency USD
```

`422` ProblemDetail, `code: UNSUPPORTED_CURRENCY` — this assignment is single-currency EUR (ADR/rest-api.md).

## `GET /bank-transactions/{paymentId}`

Role `bank:read`. In the current Keycloak realm **no user or client holds `bank:read` yet** — this is
an open item. The Postman request for it documents this and expects `403 FORBIDDEN` with
the `bank-simulator` token (role `bank:ingest` only) until that's resolved.
