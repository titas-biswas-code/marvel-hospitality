# room-reservation-service

The core service: creates and reads reservations, matches incoming bank payments to them,
auto-cancels bank-transfer reservations that miss their payment deadline, and requests refunds as
compensation. Owns the Postgres database `reservation` (properties, rooms, reservations, received
payments, refunds, its outbox and inbox tables). See
[ADR-0005](../docs/adr/0005-overbooking-exclusion-constraint.md) (overbooking prevention),
[ADR-0009](../docs/adr/0009-payment-matching.md) (payment matching),
[ADR-0010](../docs/adr/0010-auto-cancel-scheduler.md) (auto-cancel) and
[ADR-0011](../docs/adr/0011-credit-card-integration.md) (the synchronous credit-card check).

## Run

As part of the stack: `make up-apps` from the repo root builds and starts it on port 8080, with Swagger UI
at `http://localhost:8080/swagger-ui.html` (or the unified Swagger UI at `http://localhost:8088`).

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

- `CreateReservationUseCaseTest`, `PaymentMatcherTest`, `AutoCancelJobTest` — domain/use-case logic, no Spring context
- `ReservationControllerTest`, `UnmatchedPaymentControllerTest` — web layer
- `BankTransferPaymentConsumerIntegrationTest`, `RefundCompletedConsumerIntegrationTest`, `AutoCancelIntegrationTest` — `@SpringBootTest` with Testcontainers Postgres/Kafka
- `ReservationOutboxCdcTest`, `RefundRoundTripCdcTest` — outbox row through a real Debezium Connect container (`@Tag("cdc")`)
- `CreditCardReservationIntegrationTest` — WireMock stand-in for `credit-card-payment-service`
- `KeycloakRealmSmokeTest`, `SecurityWiringTest` — token validation and role/property checks
- `ArchitectureRulesTest` — source rules: `domain` has no Spring imports, no direct `now()` calls outside `Clock`, no `KafkaTemplate` in application code

## API

| Method | Path | Required authority | Does |
|---|---|---|---|
| POST | `/properties/{propertyId}/reservations` | `reservation:write` + property | Creates a reservation; CASH confirms immediately, BANK_TRANSFER goes to `PENDING_PAYMENT` with a deadline, CREDIT_CARD is checked synchronously against the credit-card service |
| GET | `/properties/{propertyId}/reservations/{reservationId}` | `reservation:read` + property | Reads one reservation |
| GET | `/properties/{propertyId}/reservations/{reservationId}/payments` | `reservation:read` + property | Lists received payments for a reservation, each with its refund status if any |
| GET | `/properties/{propertyId}/unmatched-payments` | `reservation:read` + property | Lists `UNMATCHED_NOT_PENDING` payments (arrived after the reservation stopped awaiting payment) for that property |
| GET | `/unmatched-payments` | `bank:read` | Lists `UNMATCHED_FORMAT` and `UNMATCHED_UNKNOWN_RESERVATION` payments; not property-scoped, never auto-refunded |
| GET | `/reference-data` | public | Enum values used by this service, built from `Enum.values()` |
| GET | `/whoami` | authenticated | Subject, roles and properties this service reads from the bearer token |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | public | Liveness/readiness probes; readiness includes the database and Kafka |

## Messaging

Consumed:

| Topic | Listener | Dedupe key |
|---|---|---|
| `bank-transfer-payment-update` | `BankTransferPaymentUpdateListener` | `paymentId` (inbox) |
| `refund-completed` | `RefundCompletedListener` | `refundId` (inbox) |

Produced (via the outbox; Debezium publishes the rows, this service never calls `KafkaTemplate` for domain events):

| Topic | Key | Written when |
|---|---|---|
| `reservation-status-changed` | `reservationId` | Every reservation status change |
| `refund-requested` | `paymentId` | An overpayment's surplus, or a payment for an already-cancelled/paid reservation |

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/reservation` | Postgres connection |
| `spring.datasource.username` | `DB_USERNAME` | `reservation` | Postgres role |
| `spring.datasource.password` | `DB_PASSWORD` | `reservation` | Postgres password |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka broker(s) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `JWT_ISSUER_URI` | `http://localhost:8180/realms/marvel` | Expected `iss` claim |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `JWT_JWK_SET_URI` | `http://localhost:8180/realms/marvel/protocol/openid-connect/certs` | Where signing keys are fetched |
| `marvel.security.cors-allowed-origins` | `CORS_ALLOWED_ORIGINS` | (empty, no CORS) | Browser origins allowed to call this API cross-origin |
| `reservation.id.max-attempts` | — | `5` | Attempts to generate a unique reservation id before giving up |
| `reservation.payment-service-unavailable.retry-after` | — | `5s` | `Retry-After` value on `503 PAYMENT_SERVICE_UNAVAILABLE` |
| `reservation.auto-cancel.enabled` | `RESERVATION_AUTO_CANCEL_ENABLED` | `true` | Turns the auto-cancel job on/off |
| `reservation.auto-cancel.interval` | `RESERVATION_AUTO_CANCEL_INTERVAL` | `PT60S` | Pause between the end of one run and the start of the next |
| `reservation.auto-cancel.initial-delay` | — | `PT30S` | Delay before the first run after startup |
| `reservation.auto-cancel.batch-size` | — | `100` | Rows read per page (each still cancelled in its own transaction) |
| `credit-card.client.base-url` | `CREDIT_CARD_BASE_URL` | `http://localhost:9090/credit-card-payment-api` | Base URL of `credit-card-payment-service` |
| `credit-card.client.connect-timeout` | — | `1s` | Connect timeout for the credit-card status call |
| `credit-card.client.read-timeout` | — | `2s` | Read timeout for the credit-card status call |
| `management.opentelemetry.tracing.export.otlp.endpoint` / `.logging.export.otlp.endpoint` / `management.otlp.metrics.export.url` | `OTLP_ENDPOINT` | `http://localhost:4318` (`/v1/traces`, `/v1/logs`, `/v1/metrics`) | Where traces, logs and metrics are pushed |

The credit-card call's Resilience4j policy (`resilience4j.retry` / `resilience4j.circuitbreaker`, instance
`creditCardPayment`, no env vars) is ADR-0011's: 3 attempts 200 ms apart on 5xx and I/O errors, never on 4xx; a
count-based circuit breaker over 20 calls that opens at 50 % failures (after at least 10 calls) for 10 s and lets 3
calls through half-open. An open circuit shows as `CIRCUIT_OPEN` in health but is not part of readiness.

The `local` profile (active inside compose) overrides the datasource host to `postgres`, the Kafka
bootstrap servers to `kafka:9092`, the JWK set URI to `keycloak:8080`, `CORS_ALLOWED_ORIGINS`'s default to
`http://localhost:8088`, `CREDIT_CARD_BASE_URL`'s default to `http://credit-card-payment-service:9090/credit-card-payment-api`,
the OTLP endpoint default to `http://otel-lgtm:4318`, sets structured console logging to ECS format, raises
trace sampling to 100% and shortens the metrics export step to 10s.
