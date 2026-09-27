package com.marvel.hospitality.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires {@link OutboxEventWriter} once both a {@link JdbcClient} and a {@link JsonMapper} exist. Ordered
 * {@code after} the two auto-configurations that create them, and gated on {@code @ConditionalOnBean(JdbcClient.class)}
 * rather than {@code @ConditionalOnClass} alone: a service that has this starter on its classpath but genuinely has
 * no {@code DataSource} configured (e.g. a slice test) should not fail to start just because the starter is present.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class},
        afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnBean(JdbcClient.class)
@EnableConfigurationProperties(MarvelOutboxProperties.class)
public class MarvelOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxEventWriter outboxEventWriter(
            JdbcClient jdbcClient, JsonMapper jsonMapper, ObjectProvider<Clock> clock,
            MarvelOutboxProperties properties, Environment environment, ObjectProvider<TraceparentSource> traceparent) {
        return new OutboxEventWriter(jdbcClient, jsonMapper, clock.getIfAvailable(Clock::systemUTC),
                resolveProducer(properties, environment), traceparent.getIfAvailable(TraceparentSource::none));
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

    /** Only when Micrometer Tracing is on the classpath; otherwise rows keep a {@code null} {@code traceparent}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Tracer.class, Propagator.class})
    static class TraceparentConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceparentSource micrometerTraceparentSource(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
            return new MicrometerTraceparentSource(tracer, propagator);
        }
    }

    /**
     * The slot-lag gauge (ADR-0007's replication-slot failure point). Needs a {@link MeterRegistry}, hence the
     * ordering after Micrometer's composite registry; {@code marvel.outbox.slot-lag.enabled=false} turns it off, e.g.
     * for a service whose database has no replication slot. Like the purge, it switches scheduling on only for itself.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "marvel.outbox.slot-lag", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class SlotLagConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ReplicationSlotLagMonitor replicationSlotLagMonitor(JdbcClient jdbcClient, MeterRegistry meterRegistry) {
            return new ReplicationSlotLagMonitor(jdbcClient, meterRegistry);
        }
    }

    /**
     * The daily purge. Switching it off ({@code marvel.outbox.purge.enabled=false}) also leaves scheduling alone:
     * {@code @EnableScheduling} sits on this class, not on the auto-configuration, so the starter only turns
     * scheduling on for a service that actually wants the purge. Service jobs of their own (e.g. auto-cancel) gate
     * their own beans; they must not rely on scheduling being off.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "marvel.outbox.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class OutboxPurgeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxPurgeJob outboxPurgeJob(JdbcClient jdbcClient, ObjectProvider<Clock> clock, MarvelOutboxProperties properties) {
            return new OutboxPurgeJob(jdbcClient, clock.getIfAvailable(Clock::systemUTC), properties.purge().retention());
        }
    }
}
