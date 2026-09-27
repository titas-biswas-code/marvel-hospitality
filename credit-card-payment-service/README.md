# credit-card-payment-service

An in-memory stub implementing the corrected credit-card payment provider spec
(`src/main/resources/openapi/credit-card-payment-api.yaml`) that `room-reservation-service` calls
synchronously to check a card payment before confirming a `CREDIT_CARD` reservation. It has no database and
no persistence: the outcome is a deterministic mapping from the `paymentReference` prefix (`OK`, `REJ`,
`SLOW`, `ERR`, anything else) to a status, which lets a demo or manual test drive every branch of the
consumer's client. See [ADR-0011](../docs/adr/0011-credit-card-integration.md) for the resilience policy the consumer applies
around this call, and [the defects list](../docs/credit-card-spec-defects.md) for what was corrected in the provider's spec.

## Run

As part of the stack: `make up-apps` from the repo root builds and starts it on port 9090, with Swagger UI
at `http://localhost:9090/swagger-ui.html` (or the unified Swagger UI at `http://localhost:8088`). Swagger
UI here serves the hand-corrected spec file directly rather than one generated from the controllers
(`springdoc.enable-default-api-docs: false`).

Standalone: `./gradlew build` builds and runs the test suite; it needs the sibling `platform/` directory,
since `settings.gradle` resolves `com.marvel.hospitality:*` starters with `includeBuild('../platform')`, but
unlike the other services it needs no database or broker, so no Docker/Testcontainers are required for its
own tests.

`./gradlew bootRun` starts it standalone with no other infrastructure needed (it is stateless and
in-memory); by default it also tries to push telemetry to `http://localhost:4318`, which is harmless if
nothing is listening there.

## Test

`./gradlew test` (or `build`) runs everything with JUnit 5 and Spring's MVC test slice — no Testcontainers.

- `PaymentStatusControllerTest` — every `paymentReference` prefix branch (confirmed, rejected, slow, error, not found)
- `ApplicationContextLoadsTest` — context wiring

## API

This service has no security starter and requires no bearer token (ADR-0011 notes the original spec
declares no security scheme; kept as-is, assumed network-internal).

| Method | Path | Required authority | Does |
|---|---|---|---|
| POST | `/credit-card-payment-api/payment-status` | none | Returns the payment status for a `paymentReference`: `CONFIRMED` for prefix `OK` (and `SLOW`, after a configurable delay), `REJECTED` for prefix `REJ`, `500` for prefix `ERR`, `404` for anything else |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | none | Liveness/readiness probes |

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `9090` | HTTP port |
| `credit-card-stub.slow-delay` | `CREDIT_CARD_SLOW_DELAY` | `5s` | Delay before answering `CONFIRMED` for a `SLOW`-prefixed `paymentReference`, used to drive the consumer's timeout test |
| `credit-card-stub.cors-allowed-origins` | `CREDIT_CARD_CORS_ALLOWED_ORIGINS` | (empty, no CORS) | Browser origins allowed to fetch `/v3/api-docs` cross-origin |
| `management.opentelemetry.tracing.export.otlp.endpoint` / `.logging.export.otlp.endpoint` / `management.otlp.metrics.export.url` | `OTLP_ENDPOINT` | `http://localhost:4318` (`/v1/traces`, `/v1/logs`, `/v1/metrics`) | Where traces, logs and metrics are pushed |

The `local` profile (active inside compose) overrides `CREDIT_CARD_CORS_ALLOWED_ORIGINS`'s default to
`http://localhost:8088`, the OTLP endpoint default to `http://otel-lgtm:4318`, sets structured console
logging to ECS format, raises trace sampling to 100% and shortens the metrics export step to 10s.
