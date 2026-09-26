package com.marvel.hospitality.reservation.domain;

import java.time.Instant;

/**
 * Bank-transfer reservations start {@code PENDING_PAYMENT} with a deadline (ADR-0010). A deadline that has
 * already passed at creation time means the guest cannot possibly transfer in time, so the reservation is
 * rejected outright ({@link BankTransferLeadTimeTooShortException}) rather than created and immediately
 * auto-cancelled.
 */
public final class BankTransferPaymentModeHandler implements PaymentModeHandler {

    private final PaymentDeadlinePolicy deadlinePolicy;

    public BankTransferPaymentModeHandler(PaymentDeadlinePolicy deadlinePolicy) {
        this.deadlinePolicy = deadlinePolicy;
    }

    @Override
    public PaymentMode mode() {
        return PaymentMode.BANK_TRANSFER;
    }

    @Override
    public InitialOutcome handle(PaymentModeContext context) {
        Instant deadline = deadlinePolicy.deadlineFor(context.stay().startDate(), context.property().timezone());
        if (!deadline.isAfter(context.now())) {
            throw new BankTransferLeadTimeTooShortException(deadline);
        }
        return new InitialOutcome(ReservationStatus.PENDING_PAYMENT, deadline);
    }
}
