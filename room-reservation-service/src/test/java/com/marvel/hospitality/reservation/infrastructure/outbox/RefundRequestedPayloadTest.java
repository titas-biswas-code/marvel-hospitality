package com.marvel.hospitality.reservation.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.RefundRequested;
import com.marvel.hospitality.reservation.domain.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises {@link RefundRequestedPayload} the way the application's {@link JsonMapper} does (see
 * {@link ReservationStatusChangedPayloadTest}) and checks it against the {@code refund-requested} contract (events.md):
 * field names, field order, and the rendering of the amount and the instant.
 */
class RefundRequestedPayloadTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    @Test
    void serialisesExactlyTheEventsContractFields() {
        RefundRequested event = new RefundRequested(
                UUID.fromString("d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59"),
                "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
                ReservationId.of("P4145478"),
                "AMS01",
                Money.eur("30"),
                RefundReason.OVERPAYMENT,
                Instant.parse("2026-10-01T09:16:00Z"));

        String json = JSON.writeValueAsString(RefundRequestedPayload.from(event));

        assertThat(fieldNamesInOrder(json)).containsExactly(
                "refundId", "paymentId", "reservationId", "propertyId", "amount", "currency", "reason", "requestedAt");
        assertThat(json).contains("\"refundId\":\"d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59\"");
        assertThat(json).contains("\"paymentId\":\"5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f\"");
        assertThat(json).contains("\"reservationId\":\"P4145478\"");
        assertThat(json).contains("\"propertyId\":\"AMS01\"");
        assertThat(json).contains("\"amount\":30.00");
        assertThat(json).contains("\"currency\":\"EUR\"");
        assertThat(json).contains("\"reason\":\"OVERPAYMENT\"");
        assertThat(json).contains("\"requestedAt\":\"2026-10-01T09:16:00Z\"");
    }

    /** Extracts top-level field names from a flat JSON object in the order they were written. */
    private static List<String> fieldNamesInOrder(String json) {
        return Pattern.compile("\"([a-zA-Z]+)\":")
                .matcher(json)
                .results()
                .map(match -> match.group(1))
                .toList();
    }
}
