# ADR-0003 PostgreSQL, database-per-service, Flyway, and the Postgres features we lean on

Status: Accepted · Date: 2026-09-26

## Context
We need transactional integrity for outbox/inbox patterns, a hard guarantee against double-booking,
safe multi-instance scheduling, logical decoding for CDC, and JSON storage for raw payloads.

## Decision
PostgreSQL (17+), one **database per service** inside one compose instance (separate DBs, roles and
Flyway histories), Flyway versioned migrations plus repeatable seed scripts. We use these features
deliberately, instead of writing application code:

| Need | Postgres feature | Where |
|---|---|---|
| No double-booking under concurrency | `EXCLUDE USING gist` with `btree_gist` on `(property_id, room_number, daterange)` partial on `status <> 'CANCELLED'` | ADR-0005 |
| Derived stay range / nights | `GENERATED ALWAYS AS ... STORED` columns | reservation |
| 30-day rule as a second line of defence | `CHECK (end_date - start_date <= 30)` | reservation |
| Inbox idempotency in one round trip | `INSERT ... ON CONFLICT DO NOTHING RETURNING` | all consumers |
| Multi-instance safe batch jobs | `SELECT ... FOR UPDATE SKIP LOCKED` | scheduler, purge |
| Change data capture | logical decoding (`wal_level=logical`, `pgoutput` publication) | Debezium (ADR-0007) |
| Raw/opaque payloads | `jsonb` | outbox payload, bank raw record |
| Status columns | `varchar` + `CHECK`, **not** Postgres `ENUM` types | everywhere |
| Optimistic concurrency on aggregates | `version bigint` + JPA `@Version` | reservation |

Operational rule: `pg_replication_slots` lag is exported as a metric (ADR-0013) because a stalled
Debezium slot retains WAL and can fill the disk.

## Consequences
- Tests must run against real Postgres (Testcontainers); H2 is banned. That is a feature.
- Postgres-specific SQL lives in Flyway and a few native queries; the JPA model stays portable enough.
- `ENUM` types avoided because altering them in migrations is painful; `CHECK` lists are trivially
  changed in a migration and the Java enum remains the source of truth (ADR-0004).

## Alternatives considered
- MySQL/MariaDB: no exclusion constraints, weaker range types, no `SKIP LOCKED` until recently; rejected.
- MongoDB: no multi-document atomicity guarantees we want for outbox/inbox without extra ceremony; rejected.
- Row-Level Security with `SET LOCAL app.properties` per request: excellent defence in depth for property
  isolation, but interacts awkwardly with connection pooling and would double the test surface; **future**.
- PG18 `UNIQUE ... WITHOUT OVERLAPS` (temporal constraints): standardised sibling of the exclusion
  constraint, but constraints cannot be partial, so cancelled rows would keep blocking the room; rejected.
- `pg_cron` for the auto-cancel job: moves business logic into the DB; rejected.
