package com.marvel.hospitality.reservation.domain;

/**
 * How a reservation is paid. Each mode plugs into reservation creation through a {@link PaymentModeHandler}
 * (ADR-0004) rather than a branch in the aggregate. Served via {@code GET /reference-data}; persisted as
 * {@code varchar} + {@code CHECK}, never ordinal.
 */
public enum PaymentMode {
    CASH,
    BANK_TRANSFER,
    CREDIT_CARD
}
