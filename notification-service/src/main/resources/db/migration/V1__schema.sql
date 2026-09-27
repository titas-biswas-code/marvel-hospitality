-- docs/contracts/database-schemas.md (notification) and outbox-and-inbox.md (processed_message). Keep this file
-- aligned with those contracts' constraints, column names and types; do not "improve" it here.

-- One row per rendered notification. event_id is the reservation-status-changed header `id`; the inbox already
-- guarantees one row per event, the unique constraint is the database's own guard.
CREATE TABLE notification (
  id             uuid         PRIMARY KEY,
  event_id       varchar(36)  NOT NULL UNIQUE,
  reservation_id varchar(8)   NOT NULL,
  property_id    varchar(8)   NOT NULL,
  channel        varchar(16)  NOT NULL DEFAULT 'LOG',
  template       varchar(64)  NOT NULL,     -- e.g. RESERVATION_CONFIRMED
  rendered_text  text         NOT NULL,
  created_at     timestamptz  NOT NULL
);

-- processed_message: docs/contracts/outbox-and-inbox.md (identical DDL in every consuming service).
CREATE TABLE processed_message (
  message_id   varchar(64)  NOT NULL,
  consumer     varchar(64)  NOT NULL,    -- fixed name of the consuming listener, never the consumer group
  topic        varchar(128) NOT NULL,
  processed_at timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);
