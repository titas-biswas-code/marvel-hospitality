package com.marvel.hospitality.payment.infrastructure.outbox;

import static com.marvel.hospitality.payment.KafkaTestcontainersConfiguration.REFUND_COMPLETED_TOPIC;
import static com.marvel.hospitality.payment.KafkaTestcontainersConfiguration.REFUND_REQUESTED_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.payment.KafkaListenersIntegrationTest;
import com.marvel.hospitality.payment.application.IngestBankTransactionCommand;
import com.marvel.hospitality.payment.application.IngestBankTransactionUseCase;
import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * The refund round trip's publish leg (ADR-0006/0007) against real containers: a {@code refund-requested} message
 * consumed by this service leaves an outbox row that Debezium's Outbox Event Router, configured by the committed
 * {@code infra/debezium/payment-outbox.json} (not a test copy), routes onto {@code refund-completed}. The consume
 * side (instruction creation, matching outcomes, DLT) is covered by {@link
 * com.marvel.hospitality.payment.infrastructure.kafka.RefundRequestedConsumerIntegrationTest}; this test only proves
 * the CDC leg those tests cannot see (they read {@code outbox_event} directly).
 *
 * <p>Runs in the one listening context of {@link KafkaListenersIntegrationTest}, like the consumer test.
 */
@Tag("cdc")
class RefundCompletedCdcTest extends KafkaListenersIntegrationTest {

    private static final Path CONNECTOR = Path.of("../infra/debezium/payment-outbox.json");

    @Autowired
    IngestBankTransactionUseCase ingest;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void refundRequestedRoundTripPublishesRefundCompleted() {
        DebeziumCdc.registerConnector(CONNECTOR);

        String ref = "BANK-TX-CDC-REFUND-" + UUID.randomUUID();
        String paymentId = ingest.ingest(new IngestBankTransactionCommand(ref, "NL91ABNA0417164300", "A. Lovelace",
                        new BigDecimal("30.00"), "EUR", "refund cdc test", Instant.parse("2026-10-01T09:15:00Z"),
                        "{\"bankTransactionRef\":\"" + ref + "\"}"))
                .transaction().paymentId().toString();
        String refundId = UUID.randomUUID().toString();

        String requestJson = jsonMapper.writeValueAsString(Map.of(
                "refundId", refundId,
                "paymentId", paymentId,
                "reservationId", "P4145478",
                "propertyId", "AMS01",
                "amount", new BigDecimal("30.00"),
                "currency", "EUR",
                "reason", "OVERPAYMENT",
                "requestedAt", "2026-10-01T09:16:00Z"));
        join(kafkaTemplate.send(REFUND_REQUESTED_TOPIC, paymentId, requestJson));

        ConsumerRecord<String, String> record =
                DebeziumCdc.awaitRecord(REFUND_COMPLETED_TOPIC, paymentId, Duration.ofSeconds(60));

        assertThat(record.key()).isEqualTo(paymentId);
        Headers headers = record.headers();
        assertThat(header(headers, "eventType")).isEqualTo("RefundCompleted");
        assertThat(header(headers, "producer")).isEqualTo("bank-transfer-payment-service");
        assertThat(header(headers, "propertyId")).isEqualTo("AMS01");

        JsonNode payload = jsonMapper.readTree(record.value());
        assertThat(payload.get("refundId").asString()).isEqualTo(refundId);
        assertThat(payload.get("paymentId").asString()).isEqualTo(paymentId);
        assertThat(payload.get("reservationId").asString()).isEqualTo("P4145478");
        assertThat(payload.get("propertyId").asString()).isEqualTo("AMS01");
        assertThat(payload.get("currency").asString()).isEqualTo("EUR");
        assertThat(payload.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(payload.get("failureReason").isNull()).isTrue();
        assertThat(Instant.parse(payload.get("completedAt").asString())).isNotNull();
        assertThat(record.value()).contains("\"amount\": 30.00");
        assertThat(record.value()).contains("\"status\": \"COMPLETED\"");
    }

    private static void join(CompletableFuture<?> sent) {
        try {
            sent.get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test record", e);
        }
    }

    private static String header(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        assertThat(header.value()).as("header %s value", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
