package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import java.util.Objects;

/**
 * Credit card: the reservation may only be created if the credit-card-payment-service reports the payment as
 * {@code CONFIRMED}. {@code REJECTED} and "not found" are both a {@code 422 PAYMENT_REJECTED} for the caller
 * (rest-api.md); unavailability propagates from the client as {@link PaymentServiceUnavailableException}.
 */
public class CreditCardPaymentVerification implements PaymentVerification {

    private final CreditCardPaymentClient client;

    public CreditCardPaymentVerification(CreditCardPaymentClient client) {
        this.client = client;
    }

    @Override
    public PaymentMode mode() {
        return PaymentMode.CREDIT_CARD;
    }

    @Override
    public void verify(CreateReservationCommand command) {
        // Presence is enforced at the API boundary (400 VALIDATION_FAILED); reaching here without one is a bug.
        String paymentReference = Objects.requireNonNull(command.paymentReference(), "paymentReference");
        CreditCardPaymentStatus status = client.retrieveStatus(paymentReference);
        if (status != CreditCardPaymentStatus.CONFIRMED) {
            throw new PaymentRejectedException(paymentReference, status);
        }
    }
}
