package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The payment service's answer to one {@code RefundRequested} (events.md, {@code refund-completed}).
 *
 * @param failureReason why the refund could not be paid out; {@code null} exactly when {@code completed}
 */
public record CompleteRefundCommand(
        UUID refundId,
        String paymentId,
        Money amount,
        boolean completed,
        @Nullable String failureReason,
        Instant completedAt) {

    public CompleteRefundCommand {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completed == (failureReason != null)) {
            throw new IllegalArgumentException("failureReason must be set iff the refund failed");
        }
    }
}
