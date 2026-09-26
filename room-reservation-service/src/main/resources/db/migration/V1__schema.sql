-- Reservation database schema.
-- Source of truth: docs/contracts/database-schemas.md (reservation database section) and
-- docs/contracts/outbox-and-inbox.md (outbox_event, processed_message). Keep this file byte-for-byte
-- aligned with those contracts' constraints, column names and types; do not "improve" it here.

-- Testcontainers databases never run the compose init script, and the compose init script deliberately
-- does not create this extension either (PR-00 note) -- it must be the first statement here.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE property (
  id        varchar(8)   PRIMARY KEY,           -- 'AMS01'
  name      varchar(120) NOT NULL,
  timezone  varchar(64)  NOT NULL,              -- 'Europe/Amsterdam'
  bank_account_number varchar(34) NOT NULL,     -- shown in bankTransferInstructions
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE room (
  property_id varchar(8)  NOT NULL REFERENCES property(id),
  room_number varchar(10) NOT NULL,
  segment     varchar(16) NOT NULL CHECK (segment IN ('SMALL','MEDIUM','LARGE','EXTRA_LARGE')),
  PRIMARY KEY (property_id, room_number)
);

CREATE TABLE room_rate (
  property_id  varchar(8)   NOT NULL REFERENCES property(id),
  segment      varchar(16)  NOT NULL,
  nightly_rate numeric(12,2) NOT NULL CHECK (nightly_rate > 0),
  currency     char(3)      NOT NULL DEFAULT 'EUR',
  PRIMARY KEY (property_id, segment)
);

CREATE TABLE reservation (
  id                  uuid         PRIMARY KEY,
  reservation_id      varchar(8)   NOT NULL UNIQUE,
  property_id         varchar(8)   NOT NULL REFERENCES property(id),
  room_number         varchar(10)  NOT NULL,
  customer_name       varchar(200) NOT NULL,
  start_date          date         NOT NULL,
  end_date            date         NOT NULL,
  stay                daterange    GENERATED ALWAYS AS (daterange(start_date, end_date, '[)')) STORED,
  nights              int          GENERATED ALWAYS AS (end_date - start_date) STORED,
  room_segment        varchar(16)  NOT NULL,
  payment_mode        varchar(16)  NOT NULL CHECK (payment_mode IN ('CASH','BANK_TRANSFER','CREDIT_CARD')),
  payment_reference   varchar(64),
  status              varchar(20)  NOT NULL CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','CANCELLED')),
  cancellation_reason varchar(40),
  total_amount        numeric(12,2) NOT NULL,
  amount_received     numeric(12,2) NOT NULL DEFAULT 0,
  currency            char(3)      NOT NULL DEFAULT 'EUR',
  payment_deadline_at timestamptz,
  version             bigint       NOT NULL DEFAULT 0,  -- JPA @Version
  created_at          timestamptz  NOT NULL,
  updated_at          timestamptz  NOT NULL,
  CONSTRAINT reservation_dates_chk CHECK (end_date > start_date AND end_date - start_date <= 30),
  CONSTRAINT reservation_room_fk FOREIGN KEY (property_id, room_number) REFERENCES room(property_id, room_number),
  CONSTRAINT reservation_no_overlap EXCLUDE USING gist (
      property_id WITH =, room_number WITH =, stay WITH &&
  ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX reservation_deadline_idx ON reservation (payment_deadline_at)
  WHERE status = 'PENDING_PAYMENT' AND payment_mode = 'BANK_TRANSFER';

CREATE TABLE received_payment (             -- every bank payment we have seen, matched or not
  payment_id              varchar(36)  PRIMARY KEY,   -- from the event
  reservation_id          varchar(8)   REFERENCES reservation(reservation_id),   -- null when unmatched
  property_id             varchar(8),                  -- null when unmatched
  debtor_account_number   varchar(34)  NOT NULL,
  amount                  numeric(12,2) NOT NULL,
  currency                char(3)      NOT NULL DEFAULT 'EUR',
  transaction_description varchar(255) NOT NULL,
  e2e_id                  varchar(10),
  outcome                 varchar(40)  NOT NULL CHECK (outcome IN (
      'MATCHED_PARTIAL','MATCHED_FULL','OVERPAID',
      'UNMATCHED_FORMAT','UNMATCHED_UNKNOWN_RESERVATION','UNMATCHED_NOT_PENDING')),
  received_at             timestamptz  NOT NULL
);
CREATE INDEX received_payment_reservation_idx ON received_payment (reservation_id);

CREATE TABLE refund (
  refund_id      uuid          PRIMARY KEY,
  payment_id     varchar(36)   NOT NULL REFERENCES received_payment(payment_id),
  reservation_id varchar(8)    NOT NULL REFERENCES reservation(reservation_id),
  property_id    varchar(8)    NOT NULL,
  amount         numeric(12,2) NOT NULL CHECK (amount > 0),
  currency       char(3)       NOT NULL DEFAULT 'EUR',
  reason         varchar(40)   NOT NULL CHECK (reason IN ('OVERPAYMENT','RESERVATION_CANCELLED')),
  status         varchar(20)   NOT NULL CHECK (status IN ('REQUESTED','COMPLETED','FAILED')),
  failure_reason varchar(255),
  requested_at   timestamptz   NOT NULL,
  completed_at   timestamptz
);

-- outbox_event, processed_message: docs/contracts/outbox-and-inbox.md (identical DDL in every service).
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

CREATE TABLE processed_message (
  message_id   varchar(64)  NOT NULL,
  consumer     varchar(64)  NOT NULL,    -- consumer group / listener name
  topic        varchar(128) NOT NULL,
  processed_at timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);
