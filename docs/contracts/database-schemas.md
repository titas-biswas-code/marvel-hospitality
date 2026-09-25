# Database schemas

One Postgres instance in compose, **one database per service** (`reservation`, `payment`, `notification`),
each with its own role and Flyway history. Tests spin up one Testcontainers Postgres per service.
Statuses/enums are `varchar` + `CHECK`. All timestamps `timestamptz`. Money `numeric(12,2)` + `currency char(3)`.

## reservation database
```sql
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

-- outbox_event, processed_message: see outbox-and-inbox.md
```
Seed (Flyway `R__seed_reference_data.sql`, repeatable, idempotent upserts):
- properties `AMS01` (Amsterdam, Europe/Amsterdam, NL00MARV0000000001), `RTM01` (Rotterdam, Europe/Amsterdam, NL00MARV0000000002)
- rooms per property: `101`,`102` SMALL; `201`,`202` MEDIUM; `301` LARGE; `401` EXTRA_LARGE
- rates per property: SMALL 80, MEDIUM 120, LARGE 180, EXTRA_LARGE 260 (EUR)

## payment database
```sql
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
-- outbox_event, processed_message
```

## notification database
```sql
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
-- processed_message
```
