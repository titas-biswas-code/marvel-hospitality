package com.marvel.hospitality.creditcard.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the hand-corrected {@code src/main/resources/openapi/credit-card-payment-api.yaml} verbatim at
 * {@code /v3/api-docs} instead of springdoc's own introspected document (on the classpath at
 * {@code openapi/}, not under {@code static/}, so it is served only here and not also as a static file). springdoc's generated resource at
 * that path is disabled ({@code springdoc.enable-default-api-docs: false}, application.yml) so this
 * controller can own the mapping without a clash, while Swagger UI still points at it
 * ({@code springdoc.swagger-ui.url: /v3/api-docs}). No auth: the spec declares none (ADR-0011) and this
 * endpoint must be reachable without a token for the unified Swagger UI (infra/) to fetch it cross-origin.
 */
@RestController
class OpenApiSpecController {

    private static final MediaType APPLICATION_YAML = MediaType.parseMediaType("application/yaml");

    private final Resource specResource;

    OpenApiSpecController(@Value("classpath:openapi/credit-card-payment-api.yaml") Resource specResource) {
        this.specResource = specResource;
    }

    @GetMapping(path = "/v3/api-docs", produces = "application/yaml")
    ResponseEntity<Resource> spec() {
        return ResponseEntity.ok().contentType(APPLICATION_YAML).body(specResource);
    }
}
