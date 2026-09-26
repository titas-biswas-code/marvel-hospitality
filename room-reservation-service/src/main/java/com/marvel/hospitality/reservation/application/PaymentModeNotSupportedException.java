package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMode;

/**
 * No {@code PaymentModeHandler} is registered for the requested mode. In PR-02 that is {@code CREDIT_CARD}, answered
 * with {@code 501 NOT_IMPLEMENTED_YET}; PR-03 adds its handler and removes that mapping.
 */
public class PaymentModeNotSupportedException extends RuntimeException {

    public PaymentModeNotSupportedException(PaymentMode mode) {
        super("Payment mode " + mode + " is not supported yet.");
    }
}
