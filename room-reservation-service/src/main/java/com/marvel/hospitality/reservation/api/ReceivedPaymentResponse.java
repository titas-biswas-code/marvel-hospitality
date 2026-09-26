package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A {@code received_payment} row, as returned by {@code GET .../payments} and the two unmatched-payments
 * reconciliation endpoints (rest-api.md, ADR-0009). {@code reservationId} and {@code propertyId} are
 * {@code null} exactly for the outcomes where no reservation was found at all
 * ({@link PaymentMatchOutcome#UNMATCHED_FORMAT}, {@link PaymentMatchOutcome#UNMATCHED_UNKNOWN_RESERVATION}) —
 * see {@link ReceivedPayment}'s own invariant. {@code debtorAccountNumber} and {@code transactionDescription}
 * are included because reconciliation needs them.
 */
public record ReceivedPaymentResponse(
        String paymentId,
        @Nullable String reservationId,
        @Nullable String propertyId,
        BigDecimal amount,
        String currency,
        PaymentMatchOutcome outcome,
        String transactionDescription,
        String debtorAccountNumber,
        Instant receivedAt) {

    public static ReceivedPaymentResponse from(ReceivedPayment payment) {
        return new ReceivedPaymentResponse(
                payment.paymentId(),
                payment.reservationId() == null ? null : payment.reservationId().value(),
                payment.propertyId(),
                payment.amount().amount(),
                payment.amount().currency(),
                payment.outcome(),
                payment.transactionDescription(),
                payment.debtorAccountNumber(),
                payment.receivedAt());
    }
}
