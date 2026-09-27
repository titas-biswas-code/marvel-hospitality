package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.reservation.application.CompleteRefundCommand;
import com.marvel.hospitality.reservation.domain.Money;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The value of {@code refund-completed} (events.md). A value that breaks these constraints is a contract violation:
 * it goes to the DLT without retries (ADR-0008). {@code reservationId} and {@code propertyId} are part of the
 * contract but not needed here: the stored refund already knows both.
 */
record RefundCompletedMessage(
        @NotNull UUID refundId,
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String paymentId,
        @Nullable String reservationId,
        @Nullable String propertyId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "EUR") String currency,
        @NotNull @Pattern(regexp = "COMPLETED|FAILED") String status,
        @Nullable @Size(max = 255) String failureReason,
        @NotNull Instant completedAt) {

    static final String COMPLETED = "COMPLETED";

    /** events.md: a failed refund says why, a completed one does not. */
    @AssertTrue(message = "failureReason must be set iff status is FAILED")
    boolean isFailureReasonConsistent() {
        return status == null || COMPLETED.equals(status) == (failureReason == null || failureReason.isBlank());
    }

    CompleteRefundCommand toCommand() {
        return new CompleteRefundCommand(refundId, paymentId, Money.eur(amount), COMPLETED.equals(status),
                failureReason, completedAt);
    }
}
