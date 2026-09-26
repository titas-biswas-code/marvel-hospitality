package com.marvel.hospitality.reservation.application;

/**
 * {@code 503 PAYMENT_SERVICE_UNAVAILABLE} with {@code Retry-After}: the payment could not be checked right now
 * (timeout, connection failure, 5xx after retries, or circuit breaker open). Nothing was persisted, and retrying the
 * same request later is safe because the payment check is a read-only status retrieval.
 */
public class PaymentServiceUnavailableException extends RuntimeException {

    public PaymentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
