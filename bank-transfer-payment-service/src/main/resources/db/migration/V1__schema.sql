-- Payment database schema.
-- Source of truth: docs/contracts/database-schemas.md (payment database section) and
-- docs/contracts/outbox-and-inbox.md (outbox_event, processed_message). Keep this file aligned with those
-- contracts' constraints, column names and types; do not "improve" it here.

-- The ledger of every bank transaction "the bank" has reported (ADR-0014). bank_transaction_ref is the bank's own
-- id and the idempotency key of POST /bank-transactions (contracts/identifiers.md).
CREATE TABLE bank_transaction (
  payment_id            uuid          PRIMARY KEY,
  bank_transaction_ref  varchar(64)   NOT NULL UNIQUE,
  debtor_account_number varchar(34)   NOT NULL,
  debtor_name           varchar(140),
  amount                numeric(12,2) NOT NULL CHECK (amount > 0),
  currency              char(3)       NOT NULL,
  remittance_information varchar(255) NOT NULL,
  booked_at             timestamptz   NOT NULL,
  received_at           timestamptz   NOT NULL,
  raw                   jsonb         NOT NULL
);

-- Refund execution (PR-07 fills it; created here so V1 is the whole contract schema).
CREATE TABLE refund_instruction (
  refund_id              uuid          PRIMARY KEY,
  payment_id             uuid          NOT NULL REFERENCES bank_transaction(payment_id),
  reservation_id         varchar(8)    NOT NULL,
  property_id            varchar(8)    NOT NULL,
  creditor_account_number varchar(34)  NOT NULL,   -- copied from bank_transaction.debtor_account_number
  amount                 numeric(12,2) NOT NULL CHECK (amount > 0),
  currency               char(3)       NOT NULL,
  reason                 varchar(40)   NOT NULL,
  status                 varchar(20)   NOT NULL CHECK (status IN ('RECEIVED','EXECUTED','FAILED')),
  failure_reason         varchar(255),
  created_at             timestamptz   NOT NULL,
  executed_at            timestamptz
);

-- outbox_event, processed_message: docs/contracts/outbox-and-inbox.md (identical DDL in every service).
-- Column names are read by the Debezium Outbox Event Router (infra/debezium/payment-outbox.json); never rename one
-- without changing the connector in the same PR (ADR-0007).
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
