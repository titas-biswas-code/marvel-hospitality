-- A payment triggers at most one refund (ADR-0009): the surplus of an OVERPAID payment, or the whole of an
-- UNMATCHED_NOT_PENDING one. The inbox already makes a second refund for one payment impossible in normal operation;
-- this makes the database refuse it too, so a bug surfaces as a failed transaction instead of money refunded twice.
-- It also indexes refund.payment_id, which the payments views look refunds up by.
-- Not mapped to an API error: no request can cause it. On the Kafka path it fails the payment's transaction, which is
-- retried and dead-lettered like any technical failure (ADR-0008).
ALTER TABLE refund ADD CONSTRAINT refund_payment_id_key UNIQUE (payment_id);
