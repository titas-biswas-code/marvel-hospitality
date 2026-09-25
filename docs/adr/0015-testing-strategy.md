# ADR-0015 Testing strategy

Status: Accepted · Date: 2026-09-26

## Decision
| Layer | Tool | What is proven |
|---|---|---|
| Domain (state machine, matcher, deadline calc, id generator) | JUnit 5 + AssertJ, parameterised, no Spring | every transition and every matching outcome in ADR-0004/0009 |
| Application (use cases) | JUnit + Mockito on ports | orchestration, error mapping, outbox rows written |
| Persistence | `@DataJpaTest` + Testcontainers Postgres | exclusion constraint, `ON CONFLICT` inbox, `SKIP LOCKED` query, Flyway migrations apply cleanly |
| REST | `@WebMvcTest` + `jwt()` post-processor | contract shape, validation messages, ProblemDetail codes, authz |
| Kafka | `@SpringBootTest` + Testcontainers Kafka | consumer idempotency (same paymentId twice), retry then DLT, poison message goes to DLT |
| HTTP client | WireMock | `CONFIRMED`/`REJECTED`/`404`/`500`/timeout, retry counts, circuit opens |
| Platform starters (`platform/`) | `@SpringBootTest` of a minimal app that loads the starter via its `AutoConfiguration.imports` | the shared behaviour (e.g. 401/403 codes, property checks), tested once; each service adds only a wiring test with its own settings |
| Security | one Keycloak Testcontainers smoke test | a real token from the exported realm is accepted; wrong property → 403 |
| CDC | one Testcontainers test with Postgres+Kafka+Connect (`debezium/connect`) per outbox service | outbox row becomes a message with the right key, headers, value |
| End-to-end | `infra/e2e/smoke.sh` against compose (BONUS in CI) | the README demo script |

Rules: no H2; no `Thread.sleep` (use Awaitility); `Clock` is a test double; test data builders per
aggregate; each service's `./gradlew build` runs everything without external infra.
Coverage is not a target; the named scenarios in each PR file are.
