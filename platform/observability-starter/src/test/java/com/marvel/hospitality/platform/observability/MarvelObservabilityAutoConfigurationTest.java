package com.marvel.hospitality.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.platform.observability.testapp.TestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The starter's behaviour, tested once through a minimal Boot app; services only need a wiring test.
 */
@SpringBootTest(classes = TestApplication.class, properties = "spring.application.name=observability-test-app")
class MarvelObservabilityAutoConfigurationTest {

    private static final Logger log = LoggerFactory.getLogger(MarvelObservabilityAutoConfigurationTest.class);

    @Autowired
    InMemoryLogRecordExporter exportedLogs;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void exportsLogRecordsWithMdcIdsAsAttributes() {
        exportedLogs.reset();

        try (LoggingContext ignored = LoggingContext.create().propertyId("AMS01").reservationId("P4145478")) {
            log.info("reservation stored");
        }

        List<LogRecordData> records = exportedLogs.getFinishedLogRecordItems().stream()
                .filter(r -> "reservation stored".equals(r.getBodyValue() == null ? null : r.getBodyValue().asString()))
                .toList();
        assertThat(records).hasSize(1);
        LogRecordData record = records.getFirst();
        assertThat(record.getAttributes().get(AttributeKey.stringKey("propertyId"))).isEqualTo("AMS01");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("reservationId"))).isEqualTo("P4145478");
    }

    @Test
    void exportsFluentKeyValuesAsAttributes() {
        exportedLogs.reset();

        log.atInfo().addKeyValue("status", "CONFIRMED").log("key values");

        assertThat(exportedLogs.getFinishedLogRecordItems())
                .filteredOn(r -> r.getBodyValue() != null && "key values".equals(r.getBodyValue().asString()))
                .singleElement()
                .satisfies(r -> assertThat(r.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("CONFIRMED"));
    }

    @Test
    void doesNotCopyUnrelatedMdcEntries() {
        exportedLogs.reset();

        org.slf4j.MDC.put("sessionSecret", "do-not-ship");
        try {
            log.info("unrelated mdc");
        } finally {
            org.slf4j.MDC.remove("sessionSecret");
        }

        assertThat(exportedLogs.getFinishedLogRecordItems())
                .filteredOn(r -> r.getBodyValue() != null && "unrelated mdc".equals(r.getBodyValue().asString()))
                .singleElement()
                .satisfies(r -> assertThat(r.getAttributes().get(AttributeKey.stringKey("sessionSecret"))).isNull());
    }

    @Test
    void tagsEveryMeterWithServiceName() {
        meterRegistry.counter("observability.test.counter").increment();

        assertThat(meterRegistry.get("observability.test.counter").counter().getId().getTag("service"))
                .isEqualTo("observability-test-app");
    }
}
