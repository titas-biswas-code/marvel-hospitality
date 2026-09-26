package com.marvel.hospitality.platform.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-service knobs. Everything else (issuer, JWKS) is Boot's own {@code spring.security.oauth2.resourceserver.jwt.*}.
 *
 * @param additionalPublicPaths paths that need no token on top of the contract's defaults, e.g. {@code /reference-data}
 * @param corsAllowedOrigins browser origins allowed to call the API cross-origin (the unified Swagger UI in infra/);
 *        empty means no CORS at all
 */
@ConfigurationProperties("marvel.security")
public record MarvelSecurityProperties(
        @DefaultValue List<String> additionalPublicPaths,
        @DefaultValue List<String> corsAllowedOrigins) {

    public MarvelSecurityProperties {
        additionalPublicPaths = nonBlank(additionalPublicPaths);
        corsAllowedOrigins = nonBlank(corsAllowedOrigins);
    }

    // `${CORS_ALLOWED_ORIGINS:}` binds as [""]; treat blanks as absent.
    private static List<String> nonBlank(List<String> values) {
        return values.stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }
}
