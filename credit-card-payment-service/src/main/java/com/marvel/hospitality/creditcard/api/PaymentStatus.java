package com.marvel.hospitality.creditcard.api;

/**
 * The two outcomes the corrected spec declares for {@code PaymentStatusResponse.status}
 * (src/main/resources/openapi/credit-card-payment-api.yaml). A 404/500 never reaches this enum; those are separate
 * HTTP outcomes, not a third status value (defect #3 in the original spec conflated the two).
 */
public enum PaymentStatus {
    CONFIRMED,
    REJECTED
}
