package com.marvel.hospitality.platform.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Bridges Logback to the OpenTelemetry SDK, so every log line is also exported over OTLP to Loki (ADR-0013). Boot 4
 * builds the SDK's log exporter but leaves the bridge to the application ("Loggers" in the Boot reference).
 *
 * <p>The appender is added to the root logger in code rather than in a {@code logback-spring.xml}: the console
 * format stays exactly what Boot's structured logging configures, and no service needs a Logback file of its own.
 * It copies the {@link LoggingContext#KEYS} from the MDC onto each record as attributes, which is what makes a Loki
 * query by {@code reservationId} possible, plus the key-values of SLF4J's fluent API; trace and span ids come from
 * the current OpenTelemetry context.
 *
 * <p>Logback's context outlives a Spring context (Boot initialises Logback once per JVM), so a second application
 * context in the same JVM, e.g. in tests, finds the appender already attached and only re-points it at its own SDK.
 */
class OpenTelemetryLogbackInstaller implements InitializingBean {

    static final String APPENDER_NAME = "OTEL";

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLogbackInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext loggerContext)) {
            return; // not Logback: nothing to bridge
        }
        Logger root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) == null) {
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setName(APPENDER_NAME);
            appender.setContext(loggerContext);
            appender.setCaptureMdcAttributes(String.join(",", LoggingContext.KEYS));
            // Key-values of the fluent API (log.atInfo().addKeyValue("status", ...)) become attributes too.
            appender.setCaptureKeyValuePairAttributes(true);
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(openTelemetry);
    }
}
