package com.marvel.hospitality.reservation.infrastructure.outbox;

import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.BANK_TOPIC;
import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.REFUND_COMPLETED_TOPIC;
import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.REFUND_REQUESTED_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.reservation.KafkaListenersIntegrationTest;
import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.PaymentWithRefund;
import com.marvel.hospitality.reservation.application.ReceivedPaymentQueries;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Refund;
import com.marvel.hospitality.reservation.domain.RefundStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's half of the refund saga through real Kafka and Debezium (ADR-0006/0007): an overpayment arrives on
 * the bank topic, Debezium publishes the {@code RefundRequested} the payment's transaction wrote to its outbox (routed
 * by the committed {@code infra/debezium/reservation-outbox.json}), and the payment service's answer on
 * {@code refund-completed} completes the refund.
 *
 * <p>The test plays the payment service, answering with a {@code RefundCompleted} built from the published message, not
 * from what it knows about the database. The payment service's own half is its {@code RefundCompletedCdcTest}; the
 * whole loop across both services runs in the compose demo. One JVM cannot run both services without one importing
 * the other, which ADR-0001 rules out.
 */
@Tag("cdc")
class RefundRoundTripCdcTest extends KafkaListenersIntegrationTest {

    private static final Path CONNECTOR = Path.of("../infra/debezium/reservation-outbox.json");

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CreateReservationUseCase createReservation;

    @Autowired
    ReceivedPaymentQueries payments;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void overpaymentRoundTripEndsWithRefundCompleted() throws Exception {
        DebeziumCdc.registerConnector(CONNECTOR);
        // AMS01 room 202 (MEDIUM, 120/night) in 2038: no other test books it. Two nights = 240.00.
        String reservationId = createReservation.create(new CreateReservationCommand("AMS01", "Ada Lovelace", "202",
                        LocalDate.parse("2038-03-01"), LocalDate.parse("2038-03-03"), RoomSegment.MEDIUM,
                        PaymentMode.BANK_TRANSFER, null))
                .reservation().reservationId().value();
        String paymentId = UUID.randomUUID().toString();

        kafkaTemplate.send(BANK_TOPIC, paymentId, jsonMapper.writeValueAsString(Map.of(
                "paymentId", paymentId,
                "debtorAccountnumber", "NL91ABNA0417164300",
                "amountReceived", new BigDecimal("270.00"),
                "transactionDescription", "1401541457 " + reservationId))).get();

        ConsumerRecord<String, String> requested =
                DebeziumCdc.awaitRecord(REFUND_REQUESTED_TOPIC, paymentId, Duration.ofSeconds(60));
        assertThat(requested.key()).isEqualTo(paymentId);
        assertThat(header(requested.headers(), "eventType")).isEqualTo("RefundRequested");
        assertThat(header(requested.headers(), "producer")).isEqualTo("room-reservation-service");
        assertThat(header(requested.headers(), "propertyId")).isEqualTo("AMS01");
        assertThat(requested.value()).contains("\"amount\": 30.00");
        JsonNode request = jsonMapper.readTree(requested.value());
        assertThat(request.get("paymentId").asString()).isEqualTo(paymentId);
        assertThat(request.get("reservationId").asString()).isEqualTo(reservationId);
        assertThat(request.get("reason").asString()).isEqualTo("OVERPAYMENT");
        assertThat(refundOf(reservationId, paymentId).status()).isEqualTo(RefundStatus.REQUESTED);

        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("refundId", request.get("refundId").asString());
        completed.put("paymentId", request.get("paymentId").asString());
        completed.put("reservationId", request.get("reservationId").asString());
        completed.put("propertyId", request.get("propertyId").asString());
        completed.put("amount", request.get("amount").decimalValue());
        completed.put("currency", request.get("currency").asString());
        completed.put("status", "COMPLETED");
        completed.put("failureReason", null);
        completed.put("completedAt", "2038-01-01T09:16:05Z");
        kafkaTemplate.send(REFUND_COMPLETED_TOPIC, requested.key(), jsonMapper.writeValueAsString(completed)).get();

        await().atMost(TIMEOUT).until(() -> refundOf(reservationId, paymentId).status() == RefundStatus.COMPLETED);
        Refund refund = refundOf(reservationId, paymentId);
        assertThat(refund.refundId().toString()).isEqualTo(request.get("refundId").asString());
        assertThat(refund.amount().amount()).isEqualByComparingTo("30.00");
    }

    /** Read the way {@code GET .../payments} reads it, so the round trip ends where a caller would look. */
    private Refund refundOf(String reservationId, String paymentId) {
        List<PaymentWithRefund> rows = payments.ofReservation("AMS01", reservationId);
        PaymentWithRefund row = rows.stream()
                .filter(candidate -> candidate.payment().paymentId().equals(paymentId))
                .findFirst().orElseThrow();
        assertThat(row.refund()).isNotNull();
        return row.refund();
    }

    private static String header(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        assertThat(header.value()).as("header %s value", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
