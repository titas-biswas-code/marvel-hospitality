package com.marvel.hospitality.payment.application;

/**
 * Idempotency for {@code refund-requested} (ADR-0006, outbox-and-inbox.md): remembers each {@code refundId} this
 * service has processed. Must be called inside the transaction that processes the refund, so the record and the
 * effect commit together.
 */
public interface RefundInbox {

    /** @return {@code true} the first time {@code refundId} is seen; {@code false} for a redelivery */
    boolean firstDelivery(String refundId);
}
