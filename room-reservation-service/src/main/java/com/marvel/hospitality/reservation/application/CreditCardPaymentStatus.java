package com.marvel.hospitality.reservation.application;

/**
 * What the credit-card-payment-service knows about a payment reference. {@link #NOT_FOUND} is the spec's
 * {@code 404}: an answer, not a failure, so it is neither retried nor counted against the circuit breaker.
 */
public enum CreditCardPaymentStatus {
    CONFIRMED,
    REJECTED,
    NOT_FOUND
}
