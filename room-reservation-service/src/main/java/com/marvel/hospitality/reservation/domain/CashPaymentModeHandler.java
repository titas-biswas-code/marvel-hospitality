package com.marvel.hospitality.reservation.domain;

/** Cash is collected on arrival and confirmed immediately — there is nothing to wait for. */
public final class CashPaymentModeHandler implements PaymentModeHandler {

    @Override
    public PaymentMode mode() {
        return PaymentMode.CASH;
    }

    @Override
    public InitialOutcome handle(PaymentModeContext context) {
        return new InitialOutcome(ReservationStatus.CONFIRMED, null);
    }
}
