package com.marvel.hospitality.reservation.infrastructure.creditcard;

import com.marvel.hospitality.reservation.application.CreditCardPaymentClient;
import com.marvel.hospitality.reservation.application.CreditCardPaymentStatus;
import com.marvel.hospitality.reservation.application.PaymentServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link CreditCardPaymentClient} port adapter: calls the resilient {@link CreditCardPaymentStatusGateway} and turns
 * what is left after retries into application exceptions. Timeout / connection failure
 * ({@link ResourceAccessException}), 5xx and an open circuit all mean "cannot check the payment right now"
 * ({@code 503}); any other 4xx means <em>our</em> request was wrong and surfaces as a {@code 500}.
 */
@Component
class CreditCardPaymentClientAdapter implements CreditCardPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(CreditCardPaymentClientAdapter.class);

    private final CreditCardPaymentStatusGateway gateway;

    CreditCardPaymentClientAdapter(CreditCardPaymentStatusGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public CreditCardPaymentStatus retrieveStatus(String paymentReference) {
        try {
            return gateway.retrieveStatus(paymentReference);
        } catch (CallNotPermittedException ex) {
            throw unavailable(paymentReference, "circuit breaker is open", ex);
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            throw unavailable(paymentReference, "no usable answer after retries", ex);
        } catch (HttpClientErrorException ex) {
            throw new IllegalStateException(
                    "credit-card-payment-service rejected our request with " + ex.getStatusCode(), ex);
        }
    }

    private static PaymentServiceUnavailableException unavailable(String paymentReference, String why, Exception ex) {
        log.atWarn()
                .addKeyValue("paymentReference", paymentReference)
                .addKeyValue("cause", ex.getClass().getSimpleName())
                .log("Credit-card payment service unavailable: " + why);
        return new PaymentServiceUnavailableException("The credit-card payment service is unavailable: " + why + ".", ex);
    }
}
