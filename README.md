# marvel-hospitality

Take-home for CGI Netherlands: `room-reservation-service` and the event-driven services around it.
Each service is a standalone Spring Boot 4 / Java 25 Gradle project; cross-cutting mechanism lives once in
`platform/` as Spring Boot starters (security, ProblemDetail errors, transactional outbox, inbox, Kafka consumption) that the services build
from source (ADR-0001). `make build-all` builds `platform` and then every service.
Local environment (Postgres, Kafka, Debezium, Keycloak, Grafana): see `infra/README.md`.

## Status

| Area | State |
|---|---|
| Security: Keycloak JWT validated by every service, role + property authorization (ADR-0012) | done |
| Reservations: create `CASH` (→ `CONFIRMED`) and `BANK_TRANSFER` (→ `PENDING_PAYMENT` with a payment deadline), get by id, `/reference-data`; overbooking prevented by a Postgres exclusion constraint (ADR-0005); an outbox row for every status change (ADR-0006) | done |
| Credit card: `CREDIT_CARD` reservations checked synchronously against `credit-card-payment-service` (a stub of the provided spec) through a client generated from the corrected spec, with timeouts, retry and circuit breaker (ADR-0011) | done |
| Bank payments: `bank-transfer-payment-service` ledger with idempotent `POST /bank-transactions`; its outbox and the reservation outbox published to Kafka by Debezium (ADR-0007, ADR-0014); bank simulator scripts | done |
| Payment matching: the reservation service consumes the bank topic idempotently; partial payments add up, the full amount confirms, every payment is stored with its outcome (ADR-0009). Technical failures are retried, then dead-lettered to `<topic>.DLT` (ADR-0008); watch `kafka.dlt.messages` and replay with `make replay-dlt TOPIC=…` (needs `python3`). Payments that name no known reservation are **not** refunded automatically: they wait in `GET /unmatched-payments` so a typo can still be reconciled by a person | done |
| Auto-cancel: bank-transfer reservations still `PENDING_PAYMENT` at their deadline — local midnight two days before arrival in the property's timezone — are cancelled by a job that is safe with any number of instances (per-row `FOR UPDATE SKIP LOCKED`) and after restarts (the deadline is data); a status event carries reason `PAYMENT_DEADLINE_MISSED`; a payment arriving afterwards is kept as `UNMATCHED_NOT_PENDING` (ADR-0010) | done |
| Refunds (the saga's compensation, ADR-0006): an overpayment's surplus, or a payment that arrives after cancellation or on a paid reservation, becomes a refund request in the same transaction as the payment; the payment service pays it back to the original debtor account (stub rail: accounts starting with `FAIL` are rejected) and answers on `refund-completed`. Both consumers are idempotent on `refundId`. Payment rows show their refund's status; `GET /refunds/{refundId}` on the payment service. A failed refund is logged at ERROR and counted (`refund.failed`) for a person to act on | done |
| Notifications, observability | next |

## Run it

Needs Docker (with Compose v2), `make`, `curl` and `jq`. No JDK: the services are built inside Docker.

```
git clone https://github.com/titas-biswas-code/marvel-hospitality.git && cd marvel-hospitality
make up-apps     # creates infra/.env from .env.example, builds the services, starts everything, registers the CDC connectors
```

The first run takes a few minutes (image builds). Then:

- Postman: import `docs/postman/` (collection + environment) and run the folder **"Demo: bank transfer paid in two
  parts"**: book, pay half (still `PENDING_PAYMENT`), pay the rest (`CONFIRMED`). See `docs/postman/README.md`.
- Swagger UI per service, e.g. http://localhost:8080/swagger-ui.html, or all of them at http://localhost:8088.
- Pay as "the bank" from the shell: `bank-transfer-simulator/` (see its README).

```
make down        # stop everything, keep the data
make clean       # remove containers, volumes (all data) and the built images; the next `make up-apps` starts from scratch
```

## Auto-cancel

ADR-0010. A scheduled job cancels `BANK_TRANSFER` reservations still `PENDING_PAYMENT` once their payment
deadline — local midnight two days before the stay's start date, in the property's own timezone — has passed.
Safe with any number of running instances (each row is claimed individually with `FOR UPDATE SKIP LOCKED`) and
safe across restarts (the deadline is a persisted column, not an in-memory timer, so nothing is lost by being
down across it).

| Setting | Env var | Default |
|---|---|---|
| `reservation.auto-cancel.enabled` | `RESERVATION_AUTO_CANCEL_ENABLED` | `true` |
| `reservation.auto-cancel.interval` | `RESERVATION_AUTO_CANCEL_INTERVAL` | `PT60S` |
| `reservation.auto-cancel.initial-delay` | — | `PT30S` |
| `reservation.auto-cancel.batch-size` | — | `100` |

Metrics: `reservation.autocancel.cancelled` (counter, tagged `propertyId`) and `reservation.autocancel.overdue`
(gauge — rows still due after the last run; above zero means something is failing or held elsewhere).

### See it happen

```
# 1. poll every 5 s instead of 60 s (or set RESERVATION_AUTO_CANCEL_INTERVAL in infra/.env)
RESERVATION_AUTO_CANCEL_INTERVAL=PT5S docker compose -f infra/docker-compose.yml --env-file infra/.env \
  --profile apps up -d --wait room-reservation-service

# 2. book a BANK_TRANSFER reservation (still PENDING_PAYMENT, deadline two days before arrival)
TOKEN=$(make -s token)
ID=$(curl -s -X POST http://localhost:8080/properties/AMS01/reservations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerName":"Ada Lovelace","roomNumber":"101","startDate":"2026-12-10","endDate":"2026-12-12",
       "roomSegment":"SMALL","paymentMode":"BANK_TRANSFER"}' | jq -r .reservationId)

# 3. let the deadline pass "now" (it would otherwise be days away)
docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres psql -U reservation -d reservation \
  -c "UPDATE reservation SET payment_deadline_at = now() WHERE reservation_id = '$ID'"

# 4. within one interval: CANCELLED, and a status event with reason PAYMENT_DEADLINE_MISSED on
#    reservation-status-changed (kafka-ui, http://localhost:8090)
curl -s http://localhost:8080/properties/AMS01/reservations/$ID -H "Authorization: Bearer $TOKEN" | jq .status
```

The demo edits the stored deadline instead of changing a setting. The deadline is always a local midnight, so no
"days before start" value could bring the first cancellation closer than the next midnight. Editing the row does
what the passing of time would do.

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
