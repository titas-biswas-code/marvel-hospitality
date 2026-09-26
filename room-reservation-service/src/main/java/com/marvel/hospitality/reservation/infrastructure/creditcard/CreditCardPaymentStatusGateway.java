package com.marvel.hospitality.reservation.infrastructure.creditcard;

import com.marvel.hospitality.reservation.application.CreditCardPaymentStatus;
import com.marvel.hospitality.reservation.infrastructure.creditcard.generated.api.DefaultApi;
import com.marvel.hospitality.reservation.infrastructure.creditcard.generated.model.PaymentStatusResponse;
import com.marvel.hospitality.reservation.infrastructure.creditcard.generated.model.PaymentStatusRetrievalRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The generated {@link DefaultApi} behind Resilience4j's {@code @Retry} and {@code @CircuitBreaker} (ADR-0011;
 * policy in application.yml under {@code resilience4j.*.instances.creditCardPayment}). Retry is the outer aspect
 * (Resilience4j's default order), so every attempt is recorded by the circuit breaker and an open circuit's
 * {@code CallNotPermittedException} is not retried.
 *
 * <p>A {@code 404} is turned into {@link CreditCardPaymentStatus#NOT_FOUND} <em>inside</em> the decorated method:
 * it is a definite answer, so it must be neither retried nor counted as a failure. Translating the remaining
 * exceptions happens outside the decoration, in {@link CreditCardPaymentClientAdapter}, because a fallback in here
 * would hide failures from the retry.
 */
@Component
class CreditCardPaymentStatusGateway {

    static final String RESILIENCE_INSTANCE = "creditCardPayment";

    private final DefaultApi api;

    CreditCardPaymentStatusGateway(DefaultApi api) {
        this.api = api;
    }

    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public CreditCardPaymentStatus retrieveStatus(String paymentReference) {
        PaymentStatusResponse response;
        try {
            response = api.retrievePaymentStatus(new PaymentStatusRetrievalRequest().paymentReference(paymentReference));
        } catch (HttpClientErrorException.NotFound notFound) {
            return CreditCardPaymentStatus.NOT_FOUND;
        }
        if (response == null || response.getStatus() == null) {
            throw new IllegalStateException("credit-card-payment-service answered 200 without a status");
        }
        return switch (response.getStatus()) {
            case CONFIRMED -> CreditCardPaymentStatus.CONFIRMED;
            case REJECTED -> CreditCardPaymentStatus.REJECTED;
        };
    }
}
