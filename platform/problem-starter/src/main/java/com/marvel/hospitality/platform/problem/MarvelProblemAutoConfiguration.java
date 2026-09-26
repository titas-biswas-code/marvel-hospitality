package com.marvel.hospitality.platform.problem;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link FallbackProblemAdvice} so every service gets RFC 9457 problem details for framework and
 * unexpected exceptions for free (ADR-0001: cross-cutting mechanism lives in {@code platform/}, not copied per
 * service). A service's own {@code @RestControllerAdvice} classes still run first as long as they declare
 * {@code @Order(Problems.SERVICE_ADVICE_ORDER)}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MarvelProblemAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FallbackProblemAdvice fallbackProblemAdvice() {
        return new FallbackProblemAdvice();
    }
}
