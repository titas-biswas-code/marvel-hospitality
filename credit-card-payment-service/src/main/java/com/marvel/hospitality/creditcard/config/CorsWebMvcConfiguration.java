package com.marvel.hospitality.creditcard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * This stub has no security starter at all (ADR-0011: the spec declares none), so CORS for the OpenAPI
 * document is the one cross-origin concern left to wire by hand. Only {@code /v3/api-docs} needs it: that
 * is the one request the unified Swagger UI (infra/, localhost:8088) makes cross-origin against this
 * service; the stub's own {@code /swagger-ui.html} is same-origin. Empty allow-list (the default) means no
 * CORS mapping is registered at all.
 */
@Configuration(proxyBeanMethods = false)
class CorsWebMvcConfiguration implements WebMvcConfigurer {

    private final CreditCardStubProperties properties;

    CorsWebMvcConfiguration(CreditCardStubProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!properties.corsAllowedOrigins().isEmpty()) {
            registry.addMapping("/v3/api-docs")
                    .allowedOrigins(properties.corsAllowedOrigins().toArray(String[]::new))
                    .allowedMethods("GET");
        }
    }
}
