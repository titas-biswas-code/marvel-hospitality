package com.marvel.hospitality.platform.observability;

import ch.qos.logback.classic.LoggerContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * What ADR-0013 adds on top of Boot's own OpenTelemetry auto-configuration, which already provides tracing (W3C
 * {@code traceparent} over HTTP and, with observation enabled, Kafka), {@code traceId}/{@code spanId} in the MDC and
 * OTLP export of traces, metrics and logs once their endpoints are configured.
 */
@AutoConfiguration(after = OpenTelemetrySdkAutoConfiguration.class)
public class MarvelObservabilityAutoConfiguration {

    /** Tag name every meter of every service carries (ADR-0013), from {@code spring.application.name}. */
    public static final String SERVICE_TAG = "service";

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    MeterRegistryCustomizer<MeterRegistry> marvelServiceTag(Environment environment) {
        String service = environment.getProperty("spring.application.name", "unknown");
        return registry -> registry.config().commonTags(SERVICE_TAG, service);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({OpenTelemetryAppender.class, LoggerContext.class})
    @ConditionalOnBean(OpenTelemetry.class)
    static class LogbackBridgeConfiguration {

        @Bean
        OpenTelemetryLogbackInstaller marvelOpenTelemetryLogbackInstaller(OpenTelemetry openTelemetry) {
            return new OpenTelemetryLogbackInstaller(openTelemetry);
        }
    }
}
