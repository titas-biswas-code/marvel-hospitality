# marvel-hospitality

Take-home for CGI Netherlands: `room-reservation-service` and the event-driven services around it.
Each service is a standalone Spring Boot 4 / Java 25 Gradle project; cross-cutting code (security today) lives once
in `platform/` as Spring Boot starters that the services build from source (ADR-0001). `make build-all` builds
`platform` and then every service.
Local environment (Postgres, Kafka, Debezium, Keycloak, Grafana): see `infra/README.md`.

(The full README with the end-to-end demo follows in a later PR.)
