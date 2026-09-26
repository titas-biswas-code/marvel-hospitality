package com.marvel.hospitality.platform.outbox;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires {@link OutboxEventWriter} once both a {@link JdbcClient} and a {@link JsonMapper} exist. Ordered
 * {@code after} the two auto-configurations that create them, and gated on {@code @ConditionalOnBean(JdbcClient.class)}
 * rather than {@code @ConditionalOnClass} alone: a service that has this starter on its classpath but genuinely has
 * no {@code DataSource} configured (e.g. a slice test) should not fail to start just because the starter is present.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnBean(JdbcClient.class)
@EnableConfigurationProperties(MarvelOutboxProperties.class)
public class MarvelOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxEventWriter outboxEventWriter(
            JdbcClient jdbcClient, JsonMapper jsonMapper, ObjectProvider<Clock> clock,
            MarvelOutboxProperties properties, Environment environment) {
        return new OutboxEventWriter(jdbcClient, jsonMapper, clock.getIfAvailable(Clock::systemUTC),
                resolveProducer(properties, environment));
    }

    private static String resolveProducer(MarvelOutboxProperties properties, Environment environment) {
        String producer = properties.producer();
        if (producer == null || producer.isBlank()) {
            producer = environment.getProperty("spring.application.name");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalStateException(
                    "No outbox producer name: set marvel.outbox.producer or spring.application.name.");
        }
        return producer;
    }
}
