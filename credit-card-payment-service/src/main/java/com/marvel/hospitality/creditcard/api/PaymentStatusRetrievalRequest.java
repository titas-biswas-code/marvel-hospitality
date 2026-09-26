package com.marvel.hospitality.creditcard.api;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /credit-card-payment-api/payment-status} request body
 * (docs/contracts/credit-card-payment-api.yaml: {@code PaymentStatusRetrievalRequest}). {@code @NotBlank}
 * is the whole 400 story for a missing/blank reference; malformed JSON is a separate 400 case handled by
 * {@link PaymentStatusExceptionHandling}.
 *
 * @param paymentReference reference of the payment; its prefix deterministically drives this stub's outcome
 */
public record PaymentStatusRetrievalRequest(@NotBlank String paymentReference) {
}
