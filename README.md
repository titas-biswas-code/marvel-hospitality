# marvel-hospitality

Take-home for CGI Netherlands: `room-reservation-service` and the event-driven services around it.
Each service is a standalone Spring Boot 4 / Java 25 Gradle project; cross-cutting mechanism lives once in
`platform/` as Spring Boot starters (security, ProblemDetail errors, transactional outbox) that the services build
from source (ADR-0001). `make build-all` builds `platform` and then every service.
Local environment (Postgres, Kafka, Debezium, Keycloak, Grafana): see `infra/README.md`.

## Status

| Area | State |
|---|---|
| Security: Keycloak JWT validated by every service, role + property authorization (ADR-0012) | done |
| Reservations: create `CASH` (→ `CONFIRMED`) and `BANK_TRANSFER` (→ `PENDING_PAYMENT` with a payment deadline), get by id, `/reference-data`; overbooking prevented by a Postgres exclusion constraint (ADR-0005); an outbox row for every status change (ADR-0006) | done |
| Credit card: `CREDIT_CARD` reservations checked synchronously against `credit-card-payment-service` (a stub of the provided spec) through a client generated from the corrected spec, with timeouts, retry and circuit breaker (ADR-0011) | done |
| Bank payments via Kafka/Debezium, auto-cancel, refunds, notifications, observability | next |

Try it: `make up-apps`, then Swagger UI at http://localhost:8080/swagger-ui.html (or the unified one at
http://localhost:8088), or the Postman collection in `docs/postman/` (Auth folder for a token, then "Reservations").

## Spec defects found

The provided `credit-card-payment-service` OpenAPI spec has defects. Generating a client from it as provided does
work, but the payment status comes out as a plain `String` with no allowed values, `lastUpdateDate` as a `String`
rather than a timestamp, and the server URL is unusable. The corrected copy is
`docs/contracts/credit-card-payment-api.yaml`; both the stub and the generated client in `room-reservation-service`
are built from it (ADR-0011). The spec exactly as provided is kept beside it in
`docs/contracts/credit-card-payment-api.original.yaml`, so every change can be checked with a diff. None of the
corrections changes a request or response on the wire. What was wrong in the original:

1. `servers.url` was `http//:localhost:9090//host/credit-card-payment-api` — malformed scheme, a double slash and
   a stray `host` segment. Corrected to `http://localhost:9090/credit-card-payment-api`.
2. `PaymentStatusResponse.status` declared `format: enum` with a nested list; OpenAPI needs `enum: [CONFIRMED, REJECTED]`.
   As written, generators produce a plain `String` and no allowed values.
3. `PaymentStatusResponse.status` was described as "Expiry date of the driving license" — a copy-paste leftover.
4. `lastUpdateDate` used `format: datetime`; the OpenAPI format is `date-time`, so it was not parsed as a timestamp.
5. No security scheme is declared. Kept as-is (assumed network-internal); a real deployment would use a
   service-account token (ADR-0011, ADR-0012).

One addition that is not a defect fix: an `operationId` (`retrievePaymentStatus`), which only names the generated
client method. Nothing else was tightened; in particular `status` is still not declared `required`, because the
provider does not promise it. The client treats a `200` without a status as a contract violation.

## CDC durability demo (outbox → Debezium → Kafka)

Shows that a payment is never lost even if Kafka Connect is down when it happens — it just arrives late.

1. `make up-apps`.
2. Stop Kafka Connect: `docker compose -f infra/docker-compose.yml --env-file infra/.env stop connect`.
3. Post two bank transactions while Connect is down:
   `./bank-transfer-simulator/scripts/post-bank-transaction.sh --reservation P4145478 --amount 120`
   (twice, with different `--ref`s, or via `pay-in-full.sh` against two reservations).
4. Confirm both landed in Postgres despite Connect being down:
   `docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres \
     psql -U payment -d payment -c "select aggregate_id, event_type, created_at from outbox_event order by created_at desc limit 5"`.
5. Confirm they are **not** yet on Kafka: open kafka-ui (http://localhost:8090), topic
   `bank-transfer-payment-update` — message count hasn't moved.
6. Restart Connect: `docker compose -f infra/docker-compose.yml --env-file infra/.env start connect`.
   Within a few seconds both messages appear on the topic, each with key = `paymentId` and a `propertyId` header
   whose value is null (the bank topic has no property).

Why this works: the outbox row commits in the same Postgres transaction as the ledger row, so step 3 succeeds and
is durable whatever state Connect is in. Debezium does not poll the table; it reads the write-ahead log through a
replication slot and, on restart, resumes from the slot's last confirmed position, so nothing in between is
skipped. Postgres keeps the WAL the stopped slot still needs, up to `max_slot_wal_keep_size=1GB`
(`infra/docker-compose.yml`): beyond that Postgres invalidates the slot to protect its disk, and the connector must
be re-created (runbook in `infra/README.md`). Delivery is at-least-once, so consumers deduplicate (inbox,
ADR-0006); ordering is guaranteed per key (`paymentId`), not across payments.

Design decisions: `docs/adr/`. API and event contracts: `docs/contracts/`.

(The full README with the end-to-end demo follows in a later PR.)
