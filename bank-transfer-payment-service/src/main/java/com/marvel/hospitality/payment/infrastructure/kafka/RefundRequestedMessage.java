package com.marvel.hospitality.payment.infrastructure.kafka;

import com.marvel.hospitality.payment.application.ExecuteRefundCommand;
import com.marvel.hospitality.payment.domain.RefundReason;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The value of {@code refund-requested} (events.md): the reservation service's request to refund money back to the
 * original debtor. A value that breaks these constraints is a contract violation: it goes to the DLT without
 * retries (ADR-0008).
 */
record RefundRequestedMessage(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String refundId,
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String paymentId,
        @NotBlank @Size(max = 8) String reservationId,
        @NotBlank @Size(max = 8) String propertyId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "EUR", message = "must be EUR") String currency,
        @NotNull RefundReason reason,
        @NotNull Instant requestedAt) {

    ExecuteRefundCommand toCommand() {
        return new ExecuteRefundCommand(UUID.fromString(refundId), UUID.fromString(paymentId), reservationId,
                propertyId, amount, currency, reason, requestedAt);
    }
}
