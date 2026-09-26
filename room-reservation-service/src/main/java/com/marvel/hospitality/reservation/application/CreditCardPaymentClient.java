package com.marvel.hospitality.reservation.application;

/**
 * Port to the credit-card-payment-service's {@code POST /payment-status} (src/main/resources/openapi/credit-card-payment-api.yaml).
 * A read-only status retrieval — it moves no money — so the adapter may retry it safely (ADR-0011).
 */
public interface CreditCardPaymentClient {

    /**
     * @throws PaymentServiceUnavailableException timeout, connection failure, 5xx after retries, or circuit open
     */
    CreditCardPaymentStatus retrieveStatus(String paymentReference);
}
