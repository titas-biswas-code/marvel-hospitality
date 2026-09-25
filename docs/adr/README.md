# Architecture Decision Records

Format: MADR-lite (Context → Decision → Consequences → Alternatives considered). Status is `Accepted`
unless stated. ADRs are numbered in the order the decisions were taken; later ADRs may refine earlier ones.

| # | Title |
|---|---|
| 0001 | Monorepo of independently deployable services, cross-cutting code in platform starters |
| 0002 | Property as a first-class concept; property in path, entitlement in JWT |
| 0003 | PostgreSQL, database-per-service, Flyway, and the Postgres features we lean on |
| 0004 | Reservation state machine in code; statuses persisted as text; reference-data endpoint |
| 0005 | Overbooking prevention with an exclusion constraint |
| 0006 | No distributed transactions: sagas, transactional outbox, inbox idempotency, compensation |
| 0007 | Debezium Outbox Event Router as the outbox delivery mechanism |
| 0008 | Kafka consumption: acknowledgement, retries, dead-letter topics, poison messages |
| 0009 | Bank-transfer payment matching rules |
| 0010 | Automatic cancellation scheduler |
| 0011 | Credit-card payment integration and resilience policy |
| 0012 | Security: zero-trust resource servers, Keycloak, no gateway in scope |
| 0013 | Observability |
| 0014 | bank-transfer-payment-service scope and the bank simulator |
| 0015 | Testing strategy |
