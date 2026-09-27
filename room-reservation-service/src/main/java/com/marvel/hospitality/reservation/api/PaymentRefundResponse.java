package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.domain.Refund;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The refund a payment triggered, nested in {@link ReceivedPaymentResponse} (rest-api.md). {@code failureReason} is
 * set only when {@code FAILED}; {@code completedAt} once the payment service has answered.
 */
public record PaymentRefundResponse(
        UUID refundId,
        BigDecimal amount,
        String currency,
        RefundReason reason,
        RefundStatus status,
        @Nullable String failureReason,
        Instant requestedAt,
        @Nullable Instant completedAt) {

    static PaymentRefundResponse from(Refund refund) {
        return new PaymentRefundResponse(
                refund.refundId(),
                refund.amount().amount(),
                refund.amount().currency(),
                refund.reason(),
                refund.status(),
                refund.failureReason(),
                refund.requestedAt(),
                refund.completedAt());
    }
}
