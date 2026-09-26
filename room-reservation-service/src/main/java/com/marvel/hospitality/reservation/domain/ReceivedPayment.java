package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code received_payment}: every bank payment we ever saw, matched or not (ADR-0009 §1 — persist
 * first, classify second). This is the audit/reconciliation record, distinct from {@link PaymentMatch} which
 * is just the classification result at the moment of matching.
 *
 * <p>{@code reservationId} and {@code propertyId} are only known once a payment is matched to a reservation
 * that exists, so both are {@code null} exactly for the two outcomes where no reservation was found at all
 * ({@link PaymentMatchOutcome#UNMATCHED_FORMAT}, {@link PaymentMatchOutcome#UNMATCHED_UNKNOWN_RESERVATION}).
 * {@code e2eId} is only known once {@link PaymentMatcher#parse} could read the description at all, so it is
 * {@code null} exactly for {@link PaymentMatchOutcome#UNMATCHED_FORMAT}.
 */
public record ReceivedPayment(
        String paymentId,
        @Nullable ReservationId reservationId,
        @Nullable String propertyId,
        String debtorAccountNumber,
        Money amount,
        String transactionDescription,
        @Nullable String e2eId,
        PaymentMatchOutcome outcome,
        Instant receivedAt) {

    public ReceivedPayment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(debtorAccountNumber, "debtorAccountNumber");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(transactionDescription, "transactionDescription");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(receivedAt, "receivedAt");

        boolean noReservationFound = outcome == PaymentMatchOutcome.UNMATCHED_FORMAT
                || outcome == PaymentMatchOutcome.UNMATCHED_UNKNOWN_RESERVATION;
        if (noReservationFound != (reservationId == null)) {
            throw new IllegalArgumentException("reservationId must be null iff outcome is " + outcome);
        }
        if (noReservationFound != (propertyId == null)) {
            throw new IllegalArgumentException("propertyId must be null iff outcome is " + outcome);
        }

        boolean descriptionUnreadable = outcome == PaymentMatchOutcome.UNMATCHED_FORMAT;
        if (descriptionUnreadable != (e2eId == null)) {
            throw new IllegalArgumentException("e2eId must be null iff outcome is UNMATCHED_FORMAT, got " + outcome);
        }
    }
}
