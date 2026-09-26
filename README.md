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
| Credit card, bank payments via Kafka/Debezium, auto-cancel, refunds, notifications, observability | next |

Try it: `make up-apps`, then Swagger UI at http://localhost:8080/swagger-ui.html (or the unified one at
http://localhost:8088), or the Postman collection in `docs/postman/` (Auth folder for a token, then "Reservations").

Design decisions: `docs/adr/`. API and event contracts: `docs/contracts/`.

(The full README with the end-to-end demo follows in a later PR.)
