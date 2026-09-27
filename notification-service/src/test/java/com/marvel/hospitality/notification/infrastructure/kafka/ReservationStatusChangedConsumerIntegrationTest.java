package com.marvel.hospitality.notification.infrastructure.kafka;

import static com.marvel.hospitality.notification.KafkaTestcontainersConfiguration.STATUS_DLT;
import static com.marvel.hospitality.notification.KafkaTestcontainersConfiguration.STATUS_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mockingDetails;

import com.marvel.hospitality.notification.KafkaListenersIntegrationTest;
import com.marvel.hospitality.notification.application.RecordNotificationCommand;
import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reservation-status-changed consumer end to end against a real broker and database (events.md,
 * outbox-and-inbox.md): a test {@code KafkaTemplate} produces what Debezium publishes from the reservation outbox —
 * the flat JSON value plus the header {@code id} — and the tests read the effects straight off {@code notification}
 * and {@code processed_message}. Rendering details are {@code NotificationRendererTest}'s job; here only the template
 * chosen and the consumption guarantees are checked.
 *
 * <p>The {@link KafkaListenersIntegrationTest} spy on the use case counts deliveries. Each test uses a fresh
 * reservation id (also the record key) and event id, so nothing is cleaned up. No sleeps: every wait is an
 * Awaitility condition.
 */
class ReservationStatusChangedConsumerIntegrationTest extends KafkaListenersIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void storesRenderedNotificationForStatusEvent() {
        String reservationId = newReservationId();
        String eventId = UUID.randomUUID().toString();

        send(reservationId, eventId, statusJson(reservationId, null, "PENDING_PAYMENT", null, "0.00"));
        awaitNotifications(reservationId, 1);

        Map<String, Object> row = jdbc.sql("""
                        SELECT event_id, property_id, channel, template, rendered_text
                          FROM notification WHERE reservation_id = :id
                        """)
                .param("id", reservationId).query().singleRow();
        assertThat(row).containsEntry("event_id", eventId)
                .containsEntry("property_id", "AMS01")
                .containsEntry("channel", "LOG")
                .containsEntry("template", "RESERVATION_CREATED_PENDING_PAYMENT");
        assertThat((String) row.get("rendered_text")).contains("Please transfer EUR 240.00");
        assertThat(jdbc.sql("SELECT consumer, topic FROM processed_message WHERE message_id = :id")
                .param("id", eventId).query().singleRow())
                .containsEntry("consumer", "reservation-status-changed")
                .containsEntry("topic", STATUS_TOPIC);
    }

    @Test
    void duplicateEventIdStoresOneNotification() {
        String reservationId = newReservationId();
        String eventId = UUID.randomUUID().toString();
        String value = statusJson(reservationId, "PENDING_PAYMENT", "CONFIRMED", "PAYMENT_RECEIVED", "240.00");

        send(reservationId, eventId, value);
        awaitNotifications(reservationId, 1);
        send(reservationId, eventId, value);

        await().atMost(TIMEOUT).until(() -> deliveries(reservationId) == 2);
        assertThat(notificationCount(reservationId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id").param("id", eventId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameEventWithNewIdIsANewNotification() {
        // The dedupe key is the header id (the outbox row), not the payload: a second outbox row with the same
        // content is a second event.
        String reservationId = newReservationId();
        String value = statusJson(reservationId, "PENDING_PAYMENT", "PENDING_PAYMENT", "PARTIAL_PAYMENT_RECEIVED",
                "120.00");

        send(reservationId, UUID.randomUUID().toString(), value);
        send(reservationId, UUID.randomUUID().toString(), value);

        awaitNotifications(reservationId, 2);
    }

    @Test
    void unknownCombinationStoredAsUnknown() {
        String reservationId = newReservationId();

        send(reservationId, UUID.randomUUID().toString(),
                statusJson(reservationId, "PENDING_PAYMENT", "CONFIRMED", "PAYMENT_DEADLINE_MISSED", "240.00"));
        awaitNotifications(reservationId, 1);

        assertThat(jdbc.sql("SELECT template FROM notification WHERE reservation_id = :id").param("id", reservationId)
                .query(String.class).single()).isEqualTo("UNKNOWN");
    }

    @Test
    void malformedEventGoesToDlt() {
        String reservationId = newReservationId();

        send(reservationId, UUID.randomUUID().toString(), "{\"reservationId\": \"" + reservationId + "\", not json");

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(STATUS_DLT, reservationId, TIMEOUT);
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(STATUS_TOPIC);
        // Not JSON: the message converter fails before the listener runs; spring-kafka never retries that.
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo("org.springframework.kafka.support.converter.ConversionException");
        assertThat(notificationCount(reservationId)).isZero();
    }

    @Test
    void eventWithoutIdHeaderGoesToDlt() {
        String reservationId = newReservationId();

        send(reservationId, null, statusJson(reservationId, null, "PENDING_PAYMENT", null, "0.00"));

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(STATUS_DLT, reservationId, TIMEOUT);
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("without header 'id'");
        assertThat(deliveries(reservationId)).isZero();
        assertThat(notificationCount(reservationId)).isZero();
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    /** Unique per test and within {@code varchar(8)}; the {@code T} prefix never clashes with generated ids. */
    private static String newReservationId() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
    }

    private String statusJson(String reservationId, @Nullable String previousStatus, String status,
            @Nullable String reason, String amountReceived) {
        Map<String, @Nullable Object> value = new LinkedHashMap<>();
        value.put("reservationId", reservationId);
        value.put("propertyId", "AMS01");
        value.put("customerName", "Ada Lovelace");
        value.put("roomNumber", "201");
        value.put("startDate", "2027-10-10");
        value.put("endDate", "2027-10-12");
        value.put("paymentMode", "BANK_TRANSFER");
        value.put("previousStatus", previousStatus);
        value.put("status", status);
        value.put("reason", reason);
        value.put("totalAmount", new BigDecimal("240.00"));
        value.put("amountReceived", new BigDecimal(amountReceived));
        value.put("currency", "EUR");
        value.put("paymentDeadlineAt", "2027-10-07T22:00:00Z");
        value.put("occurredAt", "2026-09-26T10:00:00Z");
        return jsonMapper.writeValueAsString(value);
    }

    /** Sends like Debezium's router does: key = reservationId, header {@code id} = the outbox row id. */
    private void send(String key, @Nullable String eventId, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(STATUS_TOPIC, key, value);
        if (eventId != null) {
            record.headers().add("id", eventId.getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add("eventType", "ReservationStatusChanged".getBytes(StandardCharsets.UTF_8));
        record.headers().add("propertyId", "AMS01".getBytes(StandardCharsets.UTF_8));
        join(kafkaTemplate.send(record));
    }

    private static void join(CompletableFuture<?> sent) {
        try {
            sent.get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test record", e);
        }
    }

    private void awaitNotifications(String reservationId, long expected) {
        await().atMost(TIMEOUT).until(() -> notificationCount(reservationId) == expected);
    }

    private long notificationCount(String reservationId) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE reservation_id = :id").param("id", reservationId)
                .query(Long.class).single();
    }

    private long deliveries(String reservationId) {
        return mockingDetails(recordNotification).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("record"))
                .map(invocation -> (RecordNotificationCommand) invocation.getArgument(0))
                .filter(command -> command.notice().reservationId().equals(reservationId))
                .count();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        assertThat(header.value()).as("header %s value", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
