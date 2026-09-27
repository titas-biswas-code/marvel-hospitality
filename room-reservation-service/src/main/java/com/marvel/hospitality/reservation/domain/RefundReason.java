package com.marvel.hospitality.reservation.domain;

/**
 * Why a {@link Refund} was requested (ADR-0009). Also served as reference data ({@code GET /reference-data}) and
 * mirrored by {@code refund.reason}'s check constraint.
 */
public enum RefundReason {
    OVERPAYMENT,
    RESERVATION_CANCELLED
}
