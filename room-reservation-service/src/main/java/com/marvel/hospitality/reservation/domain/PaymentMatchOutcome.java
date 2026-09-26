package com.marvel.hospitality.reservation.domain;

/**
 * How an incoming bank payment was matched to a reservation (ADR-0009). The matching logic itself lands in
 * PR-05; this PR only needs the type to exist as reference data (served via {@code GET /reference-data}) and
 * as the {@code received_payment.outcome} check constraint's set of values.
 */
public enum PaymentMatchOutcome {
    MATCHED_PARTIAL,
    MATCHED_FULL,
    OVERPAID,
    UNMATCHED_FORMAT,
    UNMATCHED_UNKNOWN_RESERVATION,
    UNMATCHED_NOT_PENDING
}
