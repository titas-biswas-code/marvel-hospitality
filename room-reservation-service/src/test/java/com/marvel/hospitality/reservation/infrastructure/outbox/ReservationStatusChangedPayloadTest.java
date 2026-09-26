package com.marvel.hospitality.reservation.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.ReservationStatusChanged;
import com.marvel.hospitality.reservation.domain.StatusChangeReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises {@link ReservationStatusChangedPayload} with a {@link JsonMapper} built the way Boot's own
 * {@code JacksonAutoConfiguration} builds the shared bean (ADR-0016, application.yml's
 * {@code spring.jackson.write.write-bigdecimal-as-plain}), and checks the result against the
 * {@code reservation-status-changed} contract (events.md) byte for byte: field names, field order, and the
 * exact rendering of amounts, nulls and instants.
 */
class ReservationStatusChangedPayloadTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    @Test
    void serialisesExactlyTheEventsContractFields() {
        ReservationStatusChanged event = new ReservationStatusChanged(
                ReservationId.of("P4145478"),
                "AMS01",
                "Ada Lovelace",
                "101",
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-12"),
                PaymentMode.BANK_TRANSFER,
                null,
                ReservationStatus.PENDING_PAYMENT,
                null,
                Money.eur("240.00"),
                Money.zeroEur(),
                Instant.parse("2026-10-07T22:00:00Z"),
                Instant.parse("2026-09-26T10:00:00Z"));

        String json = JSON.writeValueAsString(ReservationStatusChangedPayload.from(event));

        assertThat(fieldNamesInOrder(json)).containsExactly(
                "reservationId", "propertyId", "customerName", "roomNumber", "startDate", "endDate", "paymentMode",
                "previousStatus", "status", "reason", "totalAmount", "amountReceived", "currency",
                "paymentDeadlineAt", "occurredAt");
        assertThat(json).contains("\"reservationId\":\"P4145478\"");
        assertThat(json).contains("\"propertyId\":\"AMS01\"");
        assertThat(json).contains("\"customerName\":\"Ada Lovelace\"");
        assertThat(json).contains("\"roomNumber\":\"101\"");
        assertThat(json).contains("\"startDate\":\"2026-10-10\"");
        assertThat(json).contains("\"endDate\":\"2026-10-12\"");
        assertThat(json).contains("\"paymentMode\":\"BANK_TRANSFER\"");
        assertThat(json).contains("\"previousStatus\":null");
        assertThat(json).contains("\"status\":\"PENDING_PAYMENT\"");
        assertThat(json).contains("\"reason\":null");
        assertThat(json).contains("\"totalAmount\":240.00");
        assertThat(json).contains("\"amountReceived\":0.00");
        assertThat(json).contains("\"currency\":\"EUR\"");
        assertThat(json).contains("\"paymentDeadlineAt\":\"2026-10-07T22:00:00Z\"");
        assertThat(json).contains("\"occurredAt\":\"2026-09-26T10:00:00Z\"");
    }

    @Test
    void previousStatusAndReasonAreNullOnlyWhenTheDomainEventSaysSo() {
        ReservationStatusChanged confirmed = new ReservationStatusChanged(
                ReservationId.of("P4145478"),
                "AMS01",
                "Ada Lovelace",
                "101",
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-12"),
                PaymentMode.BANK_TRANSFER,
                ReservationStatus.PENDING_PAYMENT,
                ReservationStatus.CONFIRMED,
                StatusChangeReason.PAYMENT_RECEIVED,
                Money.eur("240.00"),
                Money.eur("240.00"),
                null,
                Instant.parse("2026-10-05T09:00:00Z"));

        String json = JSON.writeValueAsString(ReservationStatusChangedPayload.from(confirmed));

        assertThat(json).contains("\"previousStatus\":\"PENDING_PAYMENT\"");
        assertThat(json).contains("\"reason\":\"PAYMENT_RECEIVED\"");
        assertThat(json).contains("\"paymentDeadlineAt\":null");
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
