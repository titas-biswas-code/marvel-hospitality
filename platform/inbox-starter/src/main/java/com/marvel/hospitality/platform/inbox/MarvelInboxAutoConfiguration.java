package com.marvel.hospitality.platform.inbox;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires {@link ProcessedMessageInbox} once a {@link JdbcClient} exists. Ordered {@code after} the auto-configuration
 * that creates it, and gated on {@code @ConditionalOnBean(JdbcClient.class)} rather than {@code @ConditionalOnClass}
 * alone: a service that has this starter on its classpath but genuinely has no {@code DataSource} configured (e.g. a
 * slice test) should not fail to start just because the starter is present.
 */
@AutoConfiguration(after = JdbcClientAutoConfiguration.class)
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnBean(JdbcClient.class)
public class MarvelInboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProcessedMessageInbox processedMessageInbox(JdbcClient jdbcClient, ObjectProvider<Clock> clock) {
        return new ProcessedMessageInbox(jdbcClient, clock.getIfAvailable(Clock::systemUTC));
    }
}
