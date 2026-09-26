package com.marvel.hospitality.platform.problem.testapp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits every request at the filter-chain level (Boot's own security auto-config then backs off, exactly as it
 * would for a service's real {@code SecurityFilterChain}). {@code /test/access-denied} still exercises
 * {@code ExceptionTranslationFilter}: it throws {@code AccessDeniedException} from inside the controller, past
 * authorization, which this chain's filters still catch on the way back out.
 */
@Configuration
public class TestSecurityConfig {

    @Bean
    SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }
}
