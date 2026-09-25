package com.marvel.hospitality.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToAuthoritiesWithoutPrefix() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(Map.of(
                "preferred_username", "alice",
                "realm_access", Map.of("roles", List.of("reservation:read", "reservation:write")))));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("reservation:read", "reservation:write");
        assertThat(authentication.getName()).isEqualTo("alice");
    }

    @Test
    void grantsNoAuthoritiesWhenRealmAccessIsMissing() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(Map.of()));

        assertThat(authentication.getAuthorities()).isEmpty();
        assertThat(authentication.getName()).isEqualTo("subject-1");
    }

    @Test
    void ignoresMalformedRolesClaim() {
        AbstractAuthenticationToken authentication =
                converter.convert(jwt(Map.of("realm_access", Map.of("roles", "reservation:write"))));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject-1")
                .issuedAt(Instant.parse("2026-09-26T10:00:00Z"))
                .expiresAt(Instant.parse("2026-09-26T10:05:00Z"))
                .claims(c -> c.putAll(claims))
                .build();
    }
}
