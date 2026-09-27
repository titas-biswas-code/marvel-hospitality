# bank-transfer-payment-service

Marvel's bank adapter and payment ledger. Ingests bank transactions through an idempotent webhook, publishes
`bank-transfer-payment-update`, and owns refunds: it executes the payout for a `refund-requested` event and
answers on `refund-completed`. It knows nothing about reservations or matching — that is
`room-reservation-service`'s business. Owns the Postgres database `payment` (the `bank_transaction` ledger,
`refund_instruction`, its outbox and inbox tables). See
[ADR-0014](../docs/adr/0014-bank-transfer-payment-service.md) (this service's scope and the bank simulator),
[ADR-0007](../docs/adr/0007-debezium-outbox-router.md) (outbox/CDC) and
[ADR-0006](../docs/adr/0006-sagas-outbox-inbox.md) (the refund compensation saga).

## Run

As part of the stack: `make up-apps` from the repo root builds and starts it on port 8081, with Swagger UI
at `http://localhost:8081/swagger-ui.html` (or the unified Swagger UI at `http://localhost:8088`).

Standalone: `./gradlew build` builds and runs the full test suite; it needs Docker (Testcontainers starts
Postgres and Kafka) and the sibling `platform/` directory, since `settings.gradle` resolves
`com.marvel.hospitality:*` starters with `includeBuild('../platform')`.

`./gradlew bootRun` (no active profile) works against an already-running compose infra started with
`make up`: the base `application.yml` defaults point at the ports compose publishes to the host
(`localhost:5432`, `localhost:9094`, `localhost:8180`, `localhost:4318`). The `local` profile — the one the
Docker image activates via `SPRING_PROFILES_ACTIVE=local` — instead targets the compose-internal hostnames
(`postgres`, `kafka`, `keycloak`) and will not resolve when run directly on the host.

## Test

`./gradlew test` (or `build`) runs everything with JUnit 5 and Testcontainers, no running infra required;
`-PskipCdc` excludes the `@Tag("cdc")` tests for a faster loop. Representative classes:

- `BankTransactionTest`, `RefundInstructionTest` — domain logic, no Spring context
- `BankTransactionControllerTest`, `RefundControllerTest` — web layer
- `IngestBankTransactionConcurrencyTest` — concurrent ingestion of the same `bankTransactionRef`
- `RefundRequestedConsumerIntegrationTest` — `@SpringBootTest` with Testcontainers Postgres and Kafka: idempotency, retries, dead letters (extends the one listener context, `KafkaListenersIntegrationTest`)
- `PaymentOutboxCdcTest`, `RefundCompletedCdcTest` — outbox row through a real Debezium Connect container (`@Tag("cdc")`)
- `PaymentReceivedPayloadMatchesEventsContract`, `RefundCompletedPayloadMatchesEventsContract` — outbox payload shape
- `SecurityWiringTest`, `FlywayMigrationTest` — token/role checks and migration wiring

## API

| Method | Path | Required authority | Does |
|---|---|---|---|
| POST | `/bank-transactions` | `bank:ingest` | Ingests a bank transaction ("the bank webhook"); idempotent on `bankTransactionRef` (202 new, 200 repeat), publishes `PaymentReceived` via the outbox |
| GET | `/bank-transactions/{paymentId}` | `bank:read` | Reads one ledger entry |
| GET | `/refunds/{refundId}` | `bank:read` | Reads a refund instruction created for a `refund-requested` event |
| GET | `/whoami` | authenticated | Subject, roles and properties this service reads from the bearer token |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | public | Liveness/readiness probes; readiness includes the database and Kafka |

No endpoint is property-scoped: a bank transfer arrives before anyone knows which property it belongs to, so
only the role is checked.

## Messaging

Consumed:

| Topic | Listener | Dedupe key |
|---|---|---|
| `refund-requested` | `RefundRequestedListener` | `refundId` (inbox) |

Produced (via the outbox; Debezium publishes the rows, this service never calls `KafkaTemplate` for domain events):

| Topic | Key | Written when |
|---|---|---|
| `bank-transfer-payment-update` | `paymentId` | Every ingested bank transaction (`property_id` is null: the topic carries none) |
| `refund-completed` | `paymentId` | A refund instruction is executed, successfully or not (stub rail: accounts starting with `FAIL` are rejected) |

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8081` | HTTP port |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/payment` | Postgres connection |
| `spring.datasource.username` | `DB_USERNAME` | `payment` | Postgres role |
| `spring.datasource.password` | `DB_PASSWORD` | `payment` | Postgres password |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka broker(s) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `JWT_ISSUER_URI` | `http://localhost:8180/realms/marvel` | Expected `iss` claim |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `JWT_JWK_SET_URI` | `http://localhost:8180/realms/marvel/protocol/openid-connect/certs` | Where signing keys are fetched |
| `marvel.security.cors-allowed-origins` | `CORS_ALLOWED_ORIGINS` | (empty, no CORS) | Browser origins allowed to call this API cross-origin |
| `management.opentelemetry.tracing.export.otlp.endpoint` / `.logging.export.otlp.endpoint` / `management.otlp.metrics.export.url` | `OTLP_ENDPOINT` | `http://localhost:4318` (`/v1/traces`, `/v1/logs`, `/v1/metrics`) | Where traces, logs and metrics are pushed |

The `local` profile (active inside compose) overrides the datasource host to `postgres`, the Kafka
bootstrap servers to `kafka:9092`, the JWK set URI to `keycloak:8080`, `CORS_ALLOWED_ORIGINS`'s default to
`http://localhost:8088`, the OTLP endpoint default to `http://otel-lgtm:4318`, sets structured console
logging to ECS format, raises trace sampling to 100% and shortens the metrics export step to 10s.
