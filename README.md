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
| Bank payments: `bank-transfer-payment-service` ledger with idempotent `POST /bank-transactions`; its outbox and the reservation outbox published to Kafka by Debezium (ADR-0007, ADR-0014); bank simulator scripts | done |
| Payment matching: the reservation service consumes the bank topic idempotently; partial payments add up, the full amount confirms, every payment is stored with its outcome (ADR-0009). Technical failures are retried, then dead-lettered to `<topic>.DLT` (ADR-0008); watch `kafka.dlt.messages` and replay with `make replay-dlt TOPIC=…`. Payments that name no known reservation are **not** refunded automatically: they wait in `GET /unmatched-payments` so a typo can still be reconciled by a person | done |
| Auto-cancel, refunds, notifications, observability | next |

Try it: `make up-apps`, then Swagger UI at http://localhost:8080/swagger-ui.html (or the unified one at
http://localhost:8088), or the Postman collection in `docs/postman/` (Auth folder for a token, then "Reservations" / "Bank transactions").
Pay for a bank-transfer reservation as "the bank" with `bank-transfer-simulator/` (see its README).

## Further reading

| Topic | Where |
|---|---|
| Design decisions | [docs/adr/](docs/adr/) |
| API, event and database contracts | [docs/contracts/](docs/contracts/) |
| Defects in the provided credit-card spec, and the corrections | [docs/credit-card-spec-defects.md](docs/credit-card-spec-defects.md) |
| CDC durability demo (stop Kafka Connect, lose nothing) | [docs/cdc-durability-demo.md](docs/cdc-durability-demo.md) |
| Local infrastructure, ports, connectors, runbook | [infra/README.md](infra/README.md) |
| Bank simulator scripts | [bank-transfer-simulator/README.md](bank-transfer-simulator/README.md) |
| Resolved library and image versions | [docs/versions.md](docs/versions.md) |

(The full README with the end-to-end demo follows in a later PR.)
