package com.marvel.hospitality.reservation.domain;

/**
 * Strategy for "what happens when a reservation is created with this payment mode" (ADR-0004). Registered by
 * the application layer in a {@code Map<PaymentMode, PaymentModeHandler>}; adding a payment mode is one new
 * class plus a map entry, with no change to {@link Reservation} itself. {@code CREDIT_CARD} arrives in PR-03
 * — until then the API rejects it with {@code 501 NOT_IMPLEMENTED_YET} before this interface is consulted.
 */
public interface PaymentModeHandler {

    PaymentMode mode();

    InitialOutcome handle(PaymentModeContext context);
}
