-- Exact DDL from docs/contracts/outbox-and-inbox.md, loaded via spring.sql.init.mode=always.
CREATE TABLE outbox_event (
  id             uuid        PRIMARY KEY,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id   varchar(64) NOT NULL,
  event_type     varchar(64) NOT NULL,
  event_version  int         NOT NULL DEFAULT 1,
  topic          varchar(128) NOT NULL,
  property_id    varchar(8),
  producer       varchar(64) NOT NULL,
  traceparent    varchar(64),
  payload        jsonb       NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_event_created_at_idx ON outbox_event (created_at);
