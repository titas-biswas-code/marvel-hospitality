package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event mirroring the {@code refund-requested} Kafka contract (events.md) field for field, recorded by
 * {@link Refund#request} and turned into an outbox row in the transaction that applied the payment (ADR-0006).
 */
public record RefundRequested(
        UUID refundId,
        String paymentId,
        ReservationId reservationId,
        String propertyId,
        Money amount,
        RefundReason reason,
        Instant requestedAt) {
}
