package com.marvel.hospitality.reservation.domain;

/**
 * How an incoming bank payment was matched to a reservation (ADR-0009): the result of {@link PaymentMatcher},
 * served as reference data ({@code GET /reference-data}) and the set of values of the
 * {@code received_payment.outcome} check constraint.
 */
public enum PaymentMatchOutcome {
    MATCHED_PARTIAL,
    MATCHED_FULL,
    OVERPAID,
    UNMATCHED_FORMAT,
    UNMATCHED_UNKNOWN_RESERVATION,
    UNMATCHED_NOT_PENDING
}
