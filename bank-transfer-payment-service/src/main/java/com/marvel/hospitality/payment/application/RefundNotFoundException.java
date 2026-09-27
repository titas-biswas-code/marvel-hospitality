package com.marvel.hospitality.payment.application;

import java.util.UUID;

/** 404 REFUND_NOT_FOUND. */
public class RefundNotFoundException extends RuntimeException {

    public RefundNotFoundException(UUID refundId) {
        super("Refund " + refundId + " not found.");
    }
}
