package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.RefundReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One {@code refund-requested} message (events.md), already validated at the edge. */
public record ExecuteRefundCommand(
        UUID refundId, UUID paymentId, String reservationId, String propertyId, BigDecimal amount, String currency,
        RefundReason reason, Instant requestedAt) {

    public ExecuteRefundCommand {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(propertyId, "propertyId");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
