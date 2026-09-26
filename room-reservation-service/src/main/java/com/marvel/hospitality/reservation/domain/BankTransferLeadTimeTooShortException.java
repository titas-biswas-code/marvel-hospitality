package com.marvel.hospitality.reservation.domain;

import java.time.Instant;

/**
 * A bank-transfer reservation whose {@link PaymentDeadlinePolicy} deadline is already at or before "now"
 * (ADR-0010: you cannot pay by bank transfer for tonight). Maps to
 * {@code 422 BANK_TRANSFER_LEAD_TIME_TOO_SHORT} (rest-api.md); carries the computed deadline so the API layer
 * can echo it in the problem detail without recomputing it.
 */
public class BankTransferLeadTimeTooShortException extends DomainException {

    private final Instant deadline;

    public BankTransferLeadTimeTooShortException(Instant deadline) {
        super("Payment deadline " + deadline + " is not far enough in the future for a bank transfer");
        this.deadline = deadline;
    }

    public Instant deadline() {
        return deadline;
    }
}
