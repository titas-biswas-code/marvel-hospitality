package com.marvel.hospitality.platform.security;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless OAuth2 resource server (ADR-0012): every request except the public paths needs a Keycloak JWT; role and
 * property checks are per endpoint with {@code @PreAuthorize}. Runs before Boot's own security auto-configurations,
 * whose default filter chains then back off. A service that declares its own {@link SecurityFilterChain} replaces
 * the one here.
 */
@AutoConfiguration(before = {
        OAuth2ResourceServerWebSecurityAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MarvelSecurityProperties.class)
@EnableWebSecurity
@EnableMethodSecurity
public class MarvelSecurityAutoConfiguration {

    /** contracts/security.md "Public endpoints", plus the springdoc redirect and the servlet error dispatch. */
    static final List<String> DEFAULT_PUBLIC_PATHS =
            List.of("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error");

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain marvelSecurityFilterChain(
            HttpSecurity http, SecurityProblemHandler problemHandler, MarvelSecurityProperties properties) {
        String[] publicPaths = Stream.concat(DEFAULT_PUBLIC_PATHS.stream(), properties.additionalPublicPaths().stream())
                .toArray(String[]::new);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties.corsAllowedOrigins())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(publicPaths).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter()))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityProblemHandler securityProblemHandler(JsonMapper jsonMapper) {
        return new SecurityProblemHandler(jsonMapper);
    }

    /** Referenced by name from SpEL: {@code @propertyAccess.allowed(#propertyId)}. */
    @Bean(PropertyAccess.BEAN_NAME)
    @ConditionalOnMissingBean
    PropertyAccess propertyAccess() {
        return new PropertyAccess();
    }

    @Bean
    WhoamiController whoamiController() {
        return new WhoamiController();
    }

    /** With no origins configured nothing is registered and the browser's same-origin policy applies unchanged. */
    private static CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins.isEmpty()) {
            return source;
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, HttpHeaders.WWW_AUTHENTICATE));
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
