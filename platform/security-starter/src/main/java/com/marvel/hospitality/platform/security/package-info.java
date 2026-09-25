/**
 * Security for every Marvel service (ADR-0012), auto-configured by adding this starter as a dependency:
 * Keycloak JWT validation, realm roles as authorities, the {@code properties} entitlement check
 * ({@code @propertyAccess.allowed(#propertyId)}), RFC 9457 problem details for 401/403, and {@code GET /whoami}.
 */
@NullMarked
package com.marvel.hospitality.platform.security;

import org.jspecify.annotations.NullMarked;
