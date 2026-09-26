package com.marvel.hospitality.reservation.infrastructure.creditcard;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code credit-card.client.*}: where the credit-card-payment-service lives and how long to wait for it (ADR-0011).
 * Timeouts are enforced by the HTTP client itself, not by a Resilience4j {@code TimeLimiter}, because the call is
 * blocking; retry and circuit-breaker settings live under {@code resilience4j.*}.
 *
 * @param baseUrl including the spec's base path, e.g. {@code http://localhost:9090/credit-card-payment-api}
 * @param connectTimeout TCP connect timeout
 * @param readTimeout how long to wait for the response once connected
 */
@ConfigurationProperties("credit-card.client")
public record CreditCardClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
}
