package com.marvel.hospitality.creditcard.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs for the stub's deterministic behaviour (src/main/resources/openapi/credit-card-payment-api.yaml, ADR-0011).
 * {@code slowDelay} is a {@link Duration}, never a magic-number sleep, so the {@code SLOW} prefix's wait is
 * one config value that tests can override to a short duration instead of actually waiting 5 seconds.
 *
 * @param slowDelay how long the {@code SLOW} prefix sleeps before answering {@code CONFIRMED}
 * @param corsAllowedOrigins browser origins allowed to fetch {@code /v3/api-docs} cross-origin (the unified
 *        Swagger UI in infra/); empty means no CORS at all
 */
@ConfigurationProperties("credit-card-stub")
public record CreditCardStubProperties(
        @DefaultValue("5s") Duration slowDelay,
        @DefaultValue List<String> corsAllowedOrigins) {

    public CreditCardStubProperties {
        corsAllowedOrigins = nonBlank(corsAllowedOrigins);
    }

    // `${CREDIT_CARD_CORS_ALLOWED_ORIGINS:}` binds as [""]; treat blanks as absent.
    private static List<String> nonBlank(List<String> values) {
        return values.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }
}
