package com.marvel.hospitality.reservation.domain;

/**
 * Why a refund was requested. Refund creation itself is PR-07 scope; this PR only needs the type as reference
 * data (served via {@code GET /reference-data}) and as {@code refund.reason}'s check constraint values.
 */
public enum RefundReason {
    OVERPAYMENT,
    RESERVATION_CANCELLED
}
