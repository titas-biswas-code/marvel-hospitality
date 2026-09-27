package com.marvel.hospitality.reservation.infrastructure.outbox;

import com.marvel.hospitality.reservation.domain.RefundRequested;
import java.math.BigDecimal;
import java.time.Instant;

/** The exact {@code refund-requested} value shape (events.md), field for field and in the same order. */
public record RefundRequestedPayload(
        String refundId,
        String paymentId,
        String reservationId,
        String propertyId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant requestedAt) {

    public static RefundRequestedPayload from(RefundRequested event) {
        return new RefundRequestedPayload(
                event.refundId().toString(),
                event.paymentId(),
                event.reservationId().value(),
                event.propertyId(),
                event.amount().amount(),
                event.amount().currency(),
                event.reason().name(),
                event.requestedAt());
    }
}
