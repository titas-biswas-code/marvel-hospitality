package com.marvel.hospitality.payment.infrastructure.outbox;

import com.marvel.hospitality.payment.application.RefundCompletion;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The exact {@code refund-completed} value (contracts/events.md). {@code failureReason} is serialised as an explicit
 * {@code null} when {@code status} is {@code COMPLETED} (Jackson's default inclusion, unchanged by this service,
 * matches {@code reservation-status-changed}'s {@code previousStatus}, outbox-and-inbox.md).
 */
public record RefundCompletedPayload(
        String refundId, String paymentId, String reservationId, String propertyId, BigDecimal amount,
        String currency, String status, @Nullable String failureReason, Instant completedAt) {

    static final String COMPLETED = "COMPLETED";
    static final String FAILED = "FAILED";

    public static RefundCompletedPayload from(RefundCompletion completion) {
        return new RefundCompletedPayload(
                completion.refundId().toString(), completion.paymentId().toString(), completion.reservationId(),
                completion.propertyId(), completion.amount(), completion.currency(),
                completion.completed() ? COMPLETED : FAILED, completion.failureReason(), completion.completedAt());
    }
}
