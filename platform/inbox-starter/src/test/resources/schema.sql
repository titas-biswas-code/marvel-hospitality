-- Exact DDL from docs/contracts/outbox-and-inbox.md, loaded via spring.sql.init.mode=always.
CREATE TABLE processed_message (
  message_id   varchar(64)  NOT NULL,
  consumer     varchar(64)  NOT NULL,
  topic        varchar(128) NOT NULL,
  processed_at timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);
