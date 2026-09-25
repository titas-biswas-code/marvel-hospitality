package com.marvel.hospitality.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Keycloak realm roles ({@code realm_access.roles}) become authorities with their raw names, e.g.
 * {@code reservation:write}; endpoints check them with {@code hasAuthority(...)}. The authentication name is
 * {@code preferred_username} (falls back to {@code sub}). The {@code properties} claim is read on demand by
 * {@link PropertyAccess}, so it behaves the same for tokens built by spring-security-test's {@code jwt()}.
 */
public final class KeycloakRealmRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String REALM_ACCESS_CLAIM = "realm_access";
    static final String ROLES = "roles";
    static final String USERNAME_CLAIM = "preferred_username";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String username = jwt.hasClaim(USERNAME_CLAIM) ? jwt.getClaimAsString(USERNAME_CLAIM) : jwt.getSubject();
        return new JwtAuthenticationToken(jwt, realmRoles(jwt), username);
    }

    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority((String) role))
                .toList();
    }
}
