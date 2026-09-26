package com.marvel.hospitality.platform.security;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The property entitlement check (ADR-0002), used from method security:
 * {@code @PreAuthorize("hasAuthority('reservation:write') and @propertyAccess.allowed(#propertyId)")}.
 * Put the role check first: a role mismatch then yields {@code 403 FORBIDDEN}, a property mismatch
 * {@code 403 FORBIDDEN_PROPERTY}.
 *
 * <p>Entitlements come from the JWT {@code properties} claim on every call rather than being cached on the
 * authentication, so tokens built by spring-security-test's {@code jwt()} behave exactly like real ones.
 */
public class PropertyAccess {

    public static final String BEAN_NAME = "propertyAccess";
    public static final String CLAIM = "properties";
    /** Service accounts carry {@code properties: ["*"]}: every property. */
    public static final String ALL_PROPERTIES = "*";

    /**
     * @return {@code true} when the caller's token entitles it to {@code propertyId}
     * @throws PropertyAccessDeniedException otherwise; Spring Security rethrows it unwrapped from the SpEL
     *         evaluation, which lets {@link SecurityProblemHandler} tell it apart from a role mismatch
     */
    public boolean allowed(@Nullable String propertyId) {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && allows(entitledProperties(token.getToken()), propertyId)) {
            return true;
        }
        throw new PropertyAccessDeniedException(propertyId);
    }

    /** The {@code properties} claim in token order, without nulls or duplicates; missing claim = no properties. */
    public static List<String> entitledProperties(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList(CLAIM);
        return claim == null ? List.of() : claim.stream().filter(Objects::nonNull).distinct().toList();
    }

    static boolean allows(List<String> entitledProperties, @Nullable String propertyId) {
        return propertyId != null
                && (entitledProperties.contains(ALL_PROPERTIES) || entitledProperties.contains(propertyId));
    }

    /** Authenticated and holding the role, but the path's property is not in the token's {@code properties}. */
    public static class PropertyAccessDeniedException extends AuthorizationDeniedException {

        private final @Nullable String propertyId;

        public PropertyAccessDeniedException(@Nullable String propertyId) {
            super("Not entitled to property " + propertyId);
            this.propertyId = propertyId;
        }

        public @Nullable String propertyId() {
            return propertyId;
        }
    }
}
