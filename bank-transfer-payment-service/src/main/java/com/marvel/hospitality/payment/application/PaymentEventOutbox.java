package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.BankTransaction;

/**
 * Port for the events this service publishes. Implementations write an outbox row in the caller's transaction and
 * never talk to Kafka (ADR-0006/0007).
 */
public interface PaymentEventOutbox {

    /** {@code PaymentReceived} on {@code bank-transfer-payment-update} (contracts/events.md). */
    void paymentReceived(BankTransaction transaction);
}
