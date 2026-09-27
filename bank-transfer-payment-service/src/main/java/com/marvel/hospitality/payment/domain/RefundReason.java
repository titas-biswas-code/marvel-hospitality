package com.marvel.hospitality.payment.domain;

/** Why a refund was requested (contracts/events.md {@code refund-requested}). Persisted as {@code varchar}. */
public enum RefundReason {
    OVERPAYMENT,
    RESERVATION_CANCELLED
}
