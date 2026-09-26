package com.marvel.hospitality.reservation.domain;

/**
 * Credit-card reservations are born {@code CONFIRMED} (ADR-0011). The payment itself was checked with the
 * credit-card-payment-service <em>before</em> the reservation transaction opened (the application layer's
 * {@code CreditCardPaymentVerification}); by the time this handler runs, a rejected or unknown payment has already
 * been turned away, so there is nothing left to decide here.
 */
public final class CreditCardPaymentModeHandler implements PaymentModeHandler {

    @Override
    public PaymentMode mode() {
        return PaymentMode.CREDIT_CARD;
    }

    @Override
    public InitialOutcome handle(PaymentModeContext context) {
        return new InitialOutcome(ReservationStatus.CONFIRMED, null);
    }
}
