package com.marvel.hospitality.reservation.application;

/**
 * Idempotency for {@code bank-transfer-payment-update} (ADR-0006, outbox-and-inbox.md): remembers each
 * {@code paymentId} this service has applied. Must be called inside the transaction that applies the payment, so the
 * record and the effect commit together.
 */
public interface PaymentInbox {

    /** @return {@code true} the first time {@code paymentId} is seen; {@code false} for a redelivery */
    boolean firstDelivery(String paymentId);
}
