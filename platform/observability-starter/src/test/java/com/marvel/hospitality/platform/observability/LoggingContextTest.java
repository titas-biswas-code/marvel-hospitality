package com.marvel.hospitality.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LoggingContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void setsKeysForTheScopeAndClearsThemAfter() {
        try (LoggingContext ignored = LoggingContext.create().propertyId("AMS01").reservationId("P4145478")) {
            assertThat(MDC.get("propertyId")).isEqualTo("AMS01");
            assertThat(MDC.get("reservationId")).isEqualTo("P4145478");
        }

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void restoresOuterValueWhenNestedScopeCloses() {
        try (LoggingContext outer = LoggingContext.create().paymentId("pay-1").reservationId("P0000001")) {
            try (LoggingContext inner = LoggingContext.create().reservationId("P4145478").refundId("ref-1")) {
                assertThat(MDC.get("paymentId")).isEqualTo("pay-1");
                assertThat(MDC.get("reservationId")).isEqualTo("P4145478");
                assertThat(MDC.get("refundId")).isEqualTo("ref-1");
            }
            assertThat(MDC.get("paymentId")).isEqualTo("pay-1");
            assertThat(MDC.get("reservationId")).isEqualTo("P0000001");
            assertThat(MDC.get("refundId")).isNull();
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void ignoresNullValues() {
        try (LoggingContext outer = LoggingContext.create().propertyId("AMS01")) {
            try (LoggingContext inner = LoggingContext.create().propertyId(null).paymentId(null)) {
                assertThat(MDC.get("propertyId")).isEqualTo("AMS01");
                assertThat(MDC.get("paymentId")).isNull();
            }
            assertThat(MDC.get("propertyId")).isEqualTo("AMS01");
        }
    }

    @Test
    void writesNonStringIdsAsTheirStringForm() {
        java.util.UUID refundId = java.util.UUID.fromString("7f3c1a52-0d4e-4b8a-9c1e-2f6a8b9d0e11");
        try (LoggingContext ignored = LoggingContext.create().refundId(refundId)) {
            assertThat(MDC.get("refundId")).isEqualTo(refundId.toString());
        }
    }
}
