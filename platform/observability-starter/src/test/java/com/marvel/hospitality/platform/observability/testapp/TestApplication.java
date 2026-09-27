package com.marvel.hospitality.platform.observability.testapp;

import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal service for the starter's tests; the starter is picked up through its AutoConfiguration.imports exactly
 * as in a real service. In a sub-package so component scanning cannot register the starter's beans a second time.
 */
@SpringBootApplication
public class TestApplication {

    /** Stands in for the OTLP exporter: Boot's SdkLoggerProvider picks up every LogRecordProcessor bean. */
    @Bean
    InMemoryLogRecordExporter inMemoryLogRecordExporter() {
        return InMemoryLogRecordExporter.create();
    }

    @Bean
    LogRecordProcessor inMemoryLogRecordProcessor(InMemoryLogRecordExporter exporter) {
        return SimpleLogRecordProcessor.create(exporter);
    }
}
