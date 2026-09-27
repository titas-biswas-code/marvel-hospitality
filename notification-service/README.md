# notification-service

Consumes `reservation-status-changed`, renders one notification per status change (bank-transfer booking
instructions, partial payment with the remaining amount, confirmation, cancellation after a missed payment
deadline; anything else is stored as `UNKNOWN`) and logs it — there is no real delivery channel. Owns the
Postgres database `notification` (the rendered `notification` rows and its inbox table). See
[ADR-0006](../docs/adr/0006-sagas-outbox-inbox.md) (the inbox pattern this consumer uses for idempotency)
and [ADR-0008](../docs/adr/0008-kafka-consumption.md) (retry/DLT policy).

## Run

As part of the stack: `make up-apps` from the repo root builds and starts it on port 8082, with Swagger UI
at `http://localhost:8082/swagger-ui.html` (or the unified Swagger UI at `http://localhost:8088`).

Standalone: `./gradlew build` builds and runs the full test suite; it needs Docker (Testcontainers starts
Postgres and Kafka) and the sibling `platform/` directory, since `settings.gradle` resolves
`com.marvel.hospitality:*` starters with `includeBuild('../platform')`.

`./gradlew bootRun` (no active profile) works against an already-running compose infra started with
`make up`: the base `application.yml` defaults point at the ports compose publishes to the host
(`localhost:5432`, `localhost:9094`, `localhost:8180`, `localhost:4318`). The `local` profile — the one the
Docker image activates via `SPRING_PROFILES_ACTIVE=local` — instead targets the compose-internal hostnames
(`postgres`, `kafka`, `keycloak`) and will not resolve when run directly on the host.

## Test

`./gradlew test` (or `build`) runs everything with JUnit 5 and Testcontainers, no running infra required.
Representative classes:

- `NotificationRendererTest` — one message per status change, template selection, `UNKNOWN` fallback
- `LogNotificationChannelTest` — the logging-only delivery channel
- `NotificationControllerTest` — web layer, including the property filter from the token
- `ReservationStatusChangedConsumerIntegrationTest` — `@SpringBootTest` with Testcontainers Postgres and Kafka: idempotency, retries, dead letters (extends the one listener context, `KafkaListenersIntegrationTest`)
- `ReservationStatusChangedMessageMatchesEventsContract` — inbound message shape
- `SecurityWiringTest`, `FlywayMigrationTest` — token/role checks and migration wiring

## API

| Method | Path | Required authority | Does |
|---|---|---|---|
| GET | `/notifications?reservationId=` | `reservation:read` | Lists a reservation's rendered notifications, oldest first; results are filtered to the properties in the caller's token rather than a 403, so an unknown or another property's reservation yields an empty list |
| GET | `/whoami` | authenticated | Subject, roles and properties this service reads from the bearer token |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | public | Liveness/readiness probes; readiness includes the database and Kafka |

## Messaging

Consumed:

| Topic | Listener | Dedupe key |
|---|---|---|
| `reservation-status-changed` | `ReservationStatusChangedListener` | The Kafka record's `id` header, i.e. the outbox row's id emitted by Debezium's Outbox Event Router (inbox) |

This service produces no events; it has no outbox.

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8082` | HTTP port |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/notification` | Postgres connection |
| `spring.datasource.username` | `DB_USERNAME` | `notification` | Postgres role |
| `spring.datasource.password` | `DB_PASSWORD` | `notification` | Postgres password |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka broker(s) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `JWT_ISSUER_URI` | `http://localhost:8180/realms/marvel` | Expected `iss` claim |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `JWT_JWK_SET_URI` | `http://localhost:8180/realms/marvel/protocol/openid-connect/certs` | Where signing keys are fetched |
| `marvel.security.cors-allowed-origins` | `CORS_ALLOWED_ORIGINS` | (empty, no CORS) | Browser origins allowed to call this API cross-origin |
| `management.opentelemetry.tracing.export.otlp.endpoint` / `.logging.export.otlp.endpoint` / `management.otlp.metrics.export.url` | `OTLP_ENDPOINT` | `http://localhost:4318` (`/v1/traces`, `/v1/logs`, `/v1/metrics`) | Where traces, logs and metrics are pushed |

The `local` profile (active inside compose) overrides the datasource host to `postgres`, the Kafka
bootstrap servers to `kafka:9092`, the JWK set URI to `keycloak:8080`, `CORS_ALLOWED_ORIGINS`'s default to
`http://localhost:8088`, the OTLP endpoint default to `http://otel-lgtm:4318`, sets structured console
logging to ECS format, raises trace sampling to 100% and shortens the metrics export step to 10s.
