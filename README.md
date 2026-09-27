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
| Notifications: `notification-service` consumes `reservation-status-changed` idempotently (inbox on the event's header `id`), renders one message per status change (booking with bank-transfer instructions, partial payment with the remaining amount, confirmation, cancellation after a missed deadline; anything else is stored as `UNKNOWN`) and logs it; `GET /notifications?reservationId=` lists them | done |
| Observability: one trace per saga across services, Kafka and Debezium (the outbox row stores the `traceparent`); JSON logs with `traceId`, `reservationId`, `paymentId`, `refundId`, `propertyId`, shipped to Loki; business metrics and the Debezium slot-lag gauge in Prometheus; all in one Grafana (`otel-lgtm`, ADR-0013). Readiness includes the database and Kafka | done |

## Run it

Needs Docker (with Compose v2), `make`, `curl` and `jq`. No JDK: the services are built inside Docker.

```
git clone https://github.com/titas-biswas-code/marvel-hospitality.git && cd marvel-hospitality
make up-apps     # creates infra/.env from .env.example, builds the services, starts everything, registers the CDC connectors
```

The first run takes a few minutes (image builds). Then try the core flow from the shell: book a bank transfer, let
"the bank" pay it, see it confirmed and the customer notified.

```
TOKEN=$(make -s token)                                                   # alice: AMS01 + RTM01
ID=$(curl -s -X POST http://localhost:8080/properties/AMS01/reservations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerName":"Ada Lovelace","roomNumber":"202","startDate":"2031-05-10","endDate":"2031-05-12",
       "roomSegment":"MEDIUM","paymentMode":"BANK_TRANSFER"}' | jq -r .reservationId)
bank-transfer-simulator/scripts/pay-in-full.sh --property AMS01 --reservation "$ID"    # the bank pays 240.00
sleep 2                                                                  # outbox → Debezium → Kafka → consumers
curl -s http://localhost:8080/properties/AMS01/reservations/$ID -H "Authorization: Bearer $TOKEN" | jq .status
curl -s "http://localhost:8082/notifications?reservationId=$ID" -H "Authorization: Bearer $TOKEN" | jq -r '.[].template'
docker compose -f infra/docker-compose.yml logs notification-service | grep "$ID"
```

Expect `"CONFIRMED"`, then `RESERVATION_CREATED_PENDING_PAYMENT` and `RESERVATION_CONFIRMED`. (Running it again with
the same dates answers `409 ROOM_UNAVAILABLE`: change the dates.)

```
make down        # stop everything, keep the data
make clean       # remove containers, volumes (all data) and the built images; the next `make up-apps` starts from scratch
make reset-apps  # wipe all data and start everything again, rebuilt (e.g. after pulling an edited migration)
```

## Demos

Every feature has a Postman folder that runs on its own in the Collection Runner (or newman), fetches its own tokens,
creates its own data on random dates and can be re-run any number of times. Import `docs/postman/` (collection +
environment); details in [docs/postman/README.md](docs/postman/README.md). Swagger UI: each service's
`/swagger-ui.html`, or all of them at http://localhost:8088.

| Feature | Postman folder | From the shell |
|---|---|---|
| Payment matching: a bank transfer paid in two parts (`PENDING_PAYMENT` → `CONFIRMED`) and its three notifications | Demo: bank transfer paid in two parts | the core flow above |
| Booking rules: overbooking (409), wrong property (403), segment mismatch, bank-transfer lead time, card reference reused (409), card rejected (422) | Demo: booking rules | — |
| Unmatched payments: unknown reservation or unreadable description, kept for a person, not refunded | Demo: unmatched payments | `post-bank-transaction.sh --description 'hello bank' --amount 10` |
| Refunds: an overpayment's surplus, money for an already paid reservation, a refund the bank rejects | Demo: refunds | `pay-in-full.sh --property AMS01 --reservation <id> --extra 10` (add `--debtor FAIL00000000000001` for a rejected refund) |
| Notifications for cash and card bookings; the property filter | Demo: notifications | `curl …/notifications?reservationId=<id>` as above |
| Auto-cancel at the payment deadline | — (needs the stored deadline moved) | [Auto-cancel → See it happen](#see-it-happen) |
| CDC durability: stop Kafka Connect, lose nothing | — | [docs/cdc-durability-demo.md](docs/cdc-durability-demo.md) |
| Dead letters: inspect and replay | — | `make replay-dlt TOPIC=<topic> DRY_RUN=1` |

The simulator scripts live in `bank-transfer-simulator/scripts/` ([README](bank-transfer-simulator/README.md)).

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

## Notifications

Every `reservation-status-changed` event becomes one stored, rendered notification, handed to a channel after its
transaction commits. The only channel logs the text at INFO (`reservationId` and `propertyId` in the MDC); nothing is
sent to a customer. The event carries no contact details (e-mail, phone) and no bank account number, so the booking
message points to the booking confirmation for the account to pay into. A real system would look the customer's contact
details and the property's account up by reservation before sending, and would add a delivery status plus a retrying
sender so a crash between storing and sending cannot lose a message.

## Observability

Every service sends traces, metrics and logs over OTLP to one container, `otel-lgtm` (ADR-0013). Grafana:
**http://localhost:3000** (no login) → *Dashboards* → folder **Marvel Hospitality**, provisioned from
[`infra/grafana/dashboards/`](infra/grafana/dashboards/) (a JSON file dropped there appears within seconds, no import):

| Dashboard | Shows |
|---|---|
| Marvel Hospitality | Business view: reservations by status, payments by outcome, refunds, DLT count, slot lag, p95 latency |
| Marvel – Service health | Per service: request rate, 4xx/5xx, p50/p95/p99, credit-card call latency, circuit breaker, retries, heap, GC, threads, CPU, connection pool, warnings/errors |
| Marvel – Kafka & CDC | Consumer lag per topic, records consumed, last poll, rebalances, listener time and results, DLT, Debezium slot lag |
| Marvel – Saga explorer | Type a reservation id: its log lines from every service (click *Trace* to open the saga in Tempo), recent traces with a Kafka hop, all warnings and errors |

The dashboards otel-lgtm ships itself (*JVM Overview*, *RED Metrics*) expect OpenTelemetry-agent metric names and
stay mostly empty for these Micrometer-instrumented services.

**Sampling.** Locally every trace is kept (`management.tracing.sampling.probability: 1.0`, `local` profile); Boot's
default elsewhere is 0.1. To keep 30 %, set `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.3` in a service's environment
(e.g. under `environment:` in `infra/docker-compose.yml`). The decision is taken once, where a trace starts (an HTTP
request without `traceparent`, a scheduled job), and every later hop follows it — including across Debezium, since
the flag travels in the stored `traceparent` — so a saga is kept whole or not at all. Log lines of a dropped trace
still carry its `trace_id`; only Tempo has nothing to show for it.

**One saga, one trace.** A trace does not stop at Kafka: each outbox row stores the `traceparent` of the work that
wrote it, Debezium copies it into the Kafka header, and the consumer's span continues that trace. A bank payment is
therefore a single trace: the bank's `POST /bank-transactions` → the reservation service applying it → the
notification, and for an overpayment the refund in the payment service and its completion back in the reservation
service. (The booking itself is a separate trace: the request that created it.)

**Finding your way in Grafana.**
- *Explore* shows one data source at a time: pick it in the drop-down at the top left of the page (**Loki** for
  logs, **Tempo** for traces, Prometheus for metrics). There is no sub-menu per data source.
- For a search-bar-and-results view (Splunk-style), use *Explore* → **Loki** and switch the query editor from
  *Builder* to *Code*. *Drilldown* → *Logs* is Grafana's point-and-click alternative, grouped by service.
- Grafana lets anyone in without a login, but then shows an "Unauthorized" toast on every page: the page asks for
  the user's starred dashboards and teams, which an anonymous visitor does not have. Harmless; *Sign in* as
  `admin` / `admin` makes it go away.
- Telemetry is kept in the `otel-lgtm-data` volume: it survives restarts and `make down`, and `make clean` removes it.

| To find | LogQL (Explore → Loki, *Code*) |
|---|---|
| Everything | `{service_name=~".+"}` |
| One service | `{service_name="room-reservation-service"}` |
| Free text | `{service_name=~".+"} \|= "Refund"` |
| One reservation / payment / refund | `{service_name=~".+"} \| reservationId="P6FDGTP4"` (or `paymentId=`, `refundId=`) |
| Errors only | `{service_name=~".+"} \| severity_text="ERROR"` |
| One trace's log lines | `{service_name=~".+"} \| trace_id="<trace id>"` |

Click a log line to see its fields; the **Trace** button next to `trace_id` opens the trace in Tempo. The
*Marvel – Saga explorer* dashboard does the reservation search without writing a query.

**Find the saga of one reservation** after running the flow in *Run it* (`$ID` from there):

1. Grafana → *Explore* → **Loki**, code mode:
   ```
   {service_name=~".+"} | reservationId="<your $ID>"
   ```
   Every log line about that reservation, from every service. Log lines also carry `propertyId`, and `paymentId` /
   `refundId` where known, as fields to filter on.
2. Expand a line of the payment ("Payment … OVERPAID" / "CONFIRMED") and click **Trace: …** next to `trace_id`: Tempo
   opens the whole saga as one waterfall across `bank-transfer-payment-service`, `room-reservation-service` and
   `notification-service`.
3. From any span, **Logs for this span** jumps back to Loki for that trace.

The same ids are in the console JSON (`docker compose -f infra/docker-compose.yml logs room-reservation-service`):
`traceId`, `spanId`, `reservationId`, `propertyId`, … on every line.

**Metrics** (Grafana → *Explore* → **Prometheus**; dots become underscores, counters get `_total`):
`reservation_created_total{paymentMode,status,propertyId}`, `payment_matched_total{outcome}`,
`refund_requested_total{reason}`, `refund_failed_total`, `reservation_autocancel_cancelled_total`,
`kafka_dlt_messages_total{topic}` and `debezium_slot_lag_bytes{slot}` (WAL each Debezium slot holds back, read every 30 s
by the reservation and payment services), plus Boot's HTTP, JVM and Kafka meters. Every meter is tagged `service`.

**Health.** `/actuator/health/readiness` is UP only when the service's database and Kafka answer (the compose
healthchecks use it); liveness does not depend on either.

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
