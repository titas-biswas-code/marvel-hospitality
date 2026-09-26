package com.marvel.hospitality.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces the Keycloak-backed decoder so no test depends on a running Keycloak. Tests authenticate with
 * spring-security-test's {@code jwt()}; a real bearer string is rejected like a bad signature would be.
 * Only {@code KeycloakRealmSmokeTest} validates real tokens.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockJwtDecoderConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            throw new BadJwtException("Test JwtDecoder: use spring-security-test jwt() instead of a real token");
        };
    }
}
