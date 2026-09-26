package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMode;

/**
 * {@code 409 PAYMENT_REFERENCE_ALREADY_USED}: this verified payment already backs a reservation, so it cannot confirm
 * a second one (ADR-0011). Raised by the pre-check before the payment service is called and, if two requests race,
 * by the unique index {@code reservation_credit_card_payment_reference_uq}. Nothing was persisted.
 */
public class PaymentReferenceAlreadyUsedException extends RuntimeException {

    public PaymentReferenceAlreadyUsedException(PaymentMode mode, String paymentReference) {
        super(message(mode, paymentReference));
    }

    public PaymentReferenceAlreadyUsedException(PaymentMode mode, String paymentReference, Throwable cause) {
        super(message(mode, paymentReference), cause);
    }

    private static String message(PaymentMode mode, String paymentReference) {
        return mode + " payment " + paymentReference + " is already used by another reservation.";
    }
}
