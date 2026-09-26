# Contracts

Cross-service agreements. A change here is a breaking change for someone: it goes in its own reviewed PR that
updates producer and consumer together.

| File | What |
|---|---|
| `identifiers.md` | Property ids, reservation id format, payment/refund ids, remittance format |
| `rest-api.md` | REST endpoints, DTOs, error codes for all services |
| `events.md` | Kafka topics, keys, headers, value schemas, DLT naming |
| `outbox-and-inbox.md` | `outbox_event` / `processed_message` tables, Debezium routing |
| `database-schemas.md` | Table-level schema for each service's Postgres database |
| `credit-card-payment-api.original.yaml` | The provided spec exactly as received, kept so the corrections can be diffed; not used by any build. The **corrected** spec lives with the code, in `credit-card-payment-service/src/main/resources/openapi/` and an identical copy in `room-reservation-service/src/main/resources/openapi/` (`make check-contracts`); defects: `docs/credit-card-spec-defects.md` |
| `security.md` | Keycloak realm, clients, roles, claims |
