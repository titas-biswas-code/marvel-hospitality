package com.marvel.hospitality.reservation.domain;

/**
 * Where a {@link Refund} is in the compensation step of the saga (ADR-0006): {@code REQUESTED} until the payment
 * service reports back on {@code refund-completed}, then {@code COMPLETED} or {@code FAILED}. Both outcomes are final.
 */
public enum RefundStatus {
    REQUESTED,
    COMPLETED,
    FAILED
}
