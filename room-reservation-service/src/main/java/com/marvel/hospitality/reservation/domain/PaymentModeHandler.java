package com.marvel.hospitality.reservation.domain;

/**
 * Strategy for "what happens when a reservation is created with this payment mode" (ADR-0004). Registered by
 * the application layer in a {@code Map<PaymentMode, PaymentModeHandler>}; adding a payment mode is one new
 * class plus a map entry, with no change to {@link Reservation} itself.
 *
 * <p>{@link #handle} runs inside the transaction that inserts the reservation, so it must stay pure: no I/O, no
 * remote calls. A mode that needs a remote check first (credit card, ADR-0011) does it in the application layer's
 * {@code PaymentVerification} step, before that transaction opens.
 */
public interface PaymentModeHandler {

    PaymentMode mode();

    InitialOutcome handle(PaymentModeContext context);
}
