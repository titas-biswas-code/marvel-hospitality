package com.marvel.hospitality.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.platform.security.PropertyAccess.PropertyAccessDeniedException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PropertyAccessTest {

    private final PropertyAccess propertyAccess = new PropertyAccess();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsOnlyListedProperties() {
        List<String> entitled = List.of("AMS01", "RTM01");

        assertThat(PropertyAccess.allows(entitled, "AMS01")).isTrue();
        assertThat(PropertyAccess.allows(entitled, "UTR01")).isFalse();
        assertThat(PropertyAccess.allows(entitled, null)).isFalse();
    }

    @Test
    void wildcardAllowsEveryProperty() {
        assertThat(PropertyAccess.allows(List.of("*"), "UTR01")).isTrue();
    }

    @Test
    void missingClaimEntitlesToNothing() {
        assertThat(PropertyAccess.entitledProperties(jwt(null))).isEmpty();
    }

    @Test
    void readsPropertiesClaimInTokenOrder() {
        assertThat(PropertyAccess.entitledProperties(jwt(List.of("RTM01", "AMS01", "RTM01"))))
                .containsExactly("RTM01", "AMS01");
    }

    @Test
    void allowedReturnsTrueForAnEntitledCaller() {
        authenticate(List.of("AMS01"));

        assertThat(propertyAccess.allowed("AMS01")).isTrue();
    }

    @Test
    void allowedThrowsPropertyAccessDeniedForOtherProperties() {
        authenticate(List.of("AMS01"));

        assertThatThrownBy(() -> propertyAccess.allowed("RTM01"))
                .isInstanceOf(PropertyAccessDeniedException.class)
                .extracting(e -> ((PropertyAccessDeniedException) e).propertyId())
                .isEqualTo("RTM01");
    }

    @Test
    void allowedDeniesWhenNotAuthenticatedWithAJwt() {
        assertThatThrownBy(() -> propertyAccess.allowed("AMS01")).isInstanceOf(PropertyAccessDeniedException.class);
    }

    private static void authenticate(List<String> properties) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(properties)));
    }

    private static Jwt jwt(List<String> properties) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("s");
        if (properties != null) {
            builder.claim("properties", properties);
        }
        return builder.build();
    }
}
