package com.marvel.hospitality.reservation.application;

/**
 * {@code 422 PAYMENT_REJECTED}: the payment service answered, and the answer was not "confirmed" — either
 * {@code REJECTED} or unknown reference ({@code 404}). Nothing was persisted.
 */
public class PaymentRejectedException extends RuntimeException {

    private final CreditCardPaymentStatus status;

    public PaymentRejectedException(String paymentReference, CreditCardPaymentStatus status) {
        super(status == CreditCardPaymentStatus.NOT_FOUND
                ? "Payment " + paymentReference + " was not found."
                : "Payment " + paymentReference + " is " + status + ".");
        this.status = status;
    }

    public CreditCardPaymentStatus status() {
        return status;
    }
}
