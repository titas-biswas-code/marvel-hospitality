package com.marvel.hospitality.reservation.domain;

/**
 * Why a reservation was cancelled. Only one value exists today — the auto-cancel scheduler missing the
 * payment deadline (ADR-0010, PR-06) — but the type exists on its own (rather than reusing
 * {@link StatusChangeReason}) so a future manual-cancellation reason is additive. Persisted as {@code varchar}
 * + {@code CHECK} on {@code reservation.cancellation_reason}.
 */
public enum CancellationReason {
    PAYMENT_DEADLINE_MISSED
}
