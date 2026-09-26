# Outbox and inbox

## outbox_event (identical DDL in reservation and payment databases)
```sql
CREATE TABLE outbox_event (
  id             uuid        PRIMARY KEY,               -- becomes header eventId
  aggregate_type varchar(64) NOT NULL,                  -- e.g. 'reservation', 'payment', 'refund'
  aggregate_id   varchar(64) NOT NULL,                  -- becomes the Kafka key
  event_type     varchar(64) NOT NULL,                  -- becomes header eventType
  event_version  int         NOT NULL DEFAULT 1,
  topic          varchar(128) NOT NULL,                 -- routing target
  property_id    varchar(8),                            -- nullable (bank topic)
  producer       varchar(64) NOT NULL,
  traceparent    varchar(64),
  payload        jsonb       NOT NULL,                  -- becomes the Kafka value
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_event_created_at_idx ON outbox_event (created_at);
```
- Written in the **same transaction** as the state change. The application never reads it.
- Rows are kept for auditability; a daily `@Scheduled` purge deletes rows older than 7 days
  (`DELETE ... WHERE created_at < now() - interval '7 days'`). Debezium only cares about the WAL insert.

## Debezium connector (one per database), Outbox Event Router SMT
Key settings (final JSON lives in `infra/debezium/*.json`, one flat config map per connector; the file name is the
connector name):
```
connector.class=io.debezium.connector.postgresql.PostgresConnector
plugin.name=pgoutput
publication.autocreate.mode=filtered
table.include.list=public.outbox_event
snapshot.mode=initial
skipped.operations=u,d,t
tombstones.on.delete=false
transforms=outbox
transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
transforms.outbox.route.by.field=topic
transforms.outbox.route.topic.replacement=${routedByValue}
transforms.outbox.table.field.event.key=aggregate_id
transforms.outbox.table.field.event.payload=payload
transforms.outbox.table.fields.additional.placement=event_type:header:eventType,event_version:header:eventVersion,property_id:header:propertyId,producer:header:producer,traceparent:header:traceparent,created_at:header:occurredAt
transforms.outbox.table.expand.json.payload=false
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.storage.StringConverter
```
Plus per connector: `database.hostname/port/user/dbname`, `database.password=${env:<DB>_DB_PASSWORD}` (Kafka
Connect's `EnvVarConfigProvider`, so no secret is in the JSON), `topic.prefix`, `slot.name` and `publication.name`
(`<db>_outbox`), and `extended.headers.enabled=false` (Debezium 3.x otherwise adds its own `__debezium.context.*`
headers to every message; the headers above are the whole contract).
- **The value is the `payload` column's jsonb text, unchanged** (`expand.json.payload=false` + `StringConverter`).
  Expanding the JSON inside Connect is deliberately not used: the SMT infers a Connect schema from the JSON, which
  turns decimal numbers into doubles (`120.00` would arrive as `120.0`) and, by default
  (`table.json.payload.null.behavior=ignore`), drops fields whose value is `null` (`"previousStatus": null` would
  disappear). Postgres jsonb keeps a number's scale, so amounts stay at 2 fraction digits on the wire. (jsonb
  normalises whitespace and key order; consumers parse JSON, they never compare bytes.)
- `id` is automatically emitted as header `id` by the SMT; we treat `id` and `eventId` as synonyms
  (consumers read `id`).
- A `null` column placed as a header (e.g. `property_id` on the bank topic, `traceparent` until PR-10) yields the
  header **with a null value**; it is not omitted. `created_at` renders as an ISO-8601 UTC string.
- `snapshot.mode=initial`: rows written before a connector is first registered are published by its initial
  snapshot (the router treats snapshot reads like inserts). `skipped.operations=u,d,t`: the outbox is insert-only,
  and the purge job's deletes must never reach the router.
- Postgres needs `wal_level=logical`, a replication role, and `max_replication_slots >= 2`. The `filtered`
  publication needs the role to own `outbox_event` (it does: each service's Flyway creates it), which is also why
  connectors are registered only after the services have migrated.
- `infra/connect-init` registers connectors (idempotent `PUT /connectors/<name>/config`, then waits for `RUNNING`);
  it runs in compose profile `apps`, after both outbox services are healthy.

## processed_message (inbox — identical DDL in every consuming service)
```sql
CREATE TABLE processed_message (
  message_id   varchar(64)  NOT NULL,
  consumer     varchar(64)  NOT NULL,    -- consumer group / listener name
  topic        varchar(128) NOT NULL,
  processed_at timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);
```
Consumer algorithm (inside one `@Transactional` method; ack after commit):
```
inserted = INSERT INTO processed_message(...) VALUES (...) ON CONFLICT DO NOTHING RETURNING message_id
if not inserted: log DEBUG duplicate; return   -- ack happens after tx commit
apply business effect (may write outbox rows)
```
`message_id` per topic: bank topic → `paymentId`; `refund-requested`/`refund-completed` → `refundId`;
`reservation-status-changed` → header `id`.
