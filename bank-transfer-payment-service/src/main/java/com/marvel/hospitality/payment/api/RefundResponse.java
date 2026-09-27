package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.domain.RefundInstruction;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The {@code GET /refunds/{refundId}} body (rest-api.md). */
public record RefundResponse(
        String refundId,
        String paymentId,
        BigDecimal amount,
        String currency,
        String reason,
        String status,
        @Nullable String failureReason,
        Instant createdAt,
        @Nullable Instant executedAt) {

    static RefundResponse from(RefundInstruction instruction) {
        return new RefundResponse(instruction.refundId().toString(), instruction.paymentId().toString(),
                instruction.amount(), instruction.currency(), instruction.reason().name(),
                instruction.status().name(), instruction.failureReason(), instruction.createdAt(),
                instruction.executedAt());
    }
}
