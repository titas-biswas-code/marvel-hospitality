package com.marvel.hospitality.creditcard.api;

import java.time.Instant;

/**
 * {@code 200} body for {@code POST /payment-status} (src/main/resources/openapi/credit-card-payment-api.yaml:
 * {@code PaymentStatusResponse}). {@code lastUpdateDate} always comes from the injected {@code Clock}
 * (never {@code Instant.now()}), so a fixed-clock test can assert on it exactly.
 *
 * @param lastUpdateDate when this status was produced
 * @param status the payment's status
 */
public record PaymentStatusResponse(Instant lastUpdateDate, PaymentStatus status) {
}
