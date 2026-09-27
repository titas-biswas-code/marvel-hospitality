package com.marvel.hospitality.reservation.application;

import java.util.UUID;

/**
 * Idempotency for {@code refund-completed} (ADR-0006, outbox-and-inbox.md): remembers each {@code refundId} whose
 * outcome this service has recorded. Must be called inside the transaction that records it, so the two commit together.
 */
public interface RefundCompletionInbox {

    /** @return {@code true} the first time {@code refundId} is seen; {@code false} for a redelivery */
    boolean firstDelivery(UUID refundId);
}
