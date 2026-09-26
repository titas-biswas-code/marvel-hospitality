package com.marvel.hospitality.payment.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.payment.MockJwtDecoderConfiguration;
import com.marvel.hospitality.payment.TestcontainersConfiguration;
import com.marvel.hospitality.payment.application.IngestBankTransactionCommand;
import com.marvel.hospitality.payment.application.IngestBankTransactionUseCase;
import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole publish path of ADR-0006/0007 for this service, against real containers: an ingested bank transaction's
 * outbox row goes through Debezium's Outbox Event Router, configured by the committed
 * {@code infra/debezium/payment-outbox.json} (not a test copy), onto the brief's {@code bank-transfer-payment-update}.
 * Same Spring context and Postgres as the other {@code @SpringBootTest}s; Kafka and Connect are started once per JVM
 * by the shared fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
@Tag("cdc")
class PaymentOutboxCdcTest {

    private static final String TOPIC = "bank-transfer-payment-update";
    private static final Path CONNECTOR = Path.of("../infra/debezium/payment-outbox.json");

    @Autowired
    IngestBankTransactionUseCase ingest;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void outboxRowIsPublishedToConfiguredTopicWithKeyAndHeaders() {
        DebeziumCdc.createTopics(TOPIC);
        DebeziumCdc.registerConnector(CONNECTOR);

        String ref = "BANK-TX-CDC-" + UUID.randomUUID();
        String paymentId = ingest.ingest(new IngestBankTransactionCommand(ref, "NL91ABNA0417164300", "A. Lovelace",
                        new BigDecimal("120.00"), "EUR", "1401541457 P4145478", Instant.parse("2026-10-01T09:15:00Z"),
                        "{\"bankTransactionRef\":\"" + ref + "\"}"))
                .transaction().paymentId().toString();
        Map<String, Object> row = jdbc.sql("SELECT id::text AS id, payload::text AS payload, created_at FROM outbox_event WHERE aggregate_id = :id")
                .param("id", paymentId)
                .query().singleRow();

        ConsumerRecord<String, String> record = DebeziumCdc.awaitRecord(TOPIC, paymentId, Duration.ofSeconds(60));

        assertThat(record.key()).isEqualTo(paymentId);
        Headers headers = record.headers();
        assertThat(header(headers, "id")).isEqualTo(row.get("id"));
        assertThat(header(headers, "eventType")).isEqualTo("PaymentReceived");
        assertThat(header(headers, "eventVersion")).isEqualTo("1");
        assertThat(header(headers, "producer")).isEqualTo("bank-transfer-payment-service");
        assertThat(OffsetDateTime.parse(header(headers, "occurredAt")).toInstant())
                .isEqualTo(((java.sql.Timestamp) row.get("created_at")).toInstant());
        // The bank topic has no property (events.md): the header is there, with a null value.
        assertThat(headers.lastHeader("propertyId")).isNotNull();
        assertThat(headers.lastHeader("propertyId").value()).isNull();

        // Byte for byte what the payload column holds, with the brief's field names and the amount at scale 2.
        assertThat(record.value()).isEqualTo(row.get("payload"));
        assertThat(jsonMapper.readTree(record.value())).isEqualTo(jsonMapper.readTree("""
                {"paymentId": "%s", "debtorAccountnumber": "NL91ABNA0417164300", "amountReceived": 120.00,
                 "transactionDescription": "1401541457 P4145478"}""".formatted(paymentId)));
        assertThat(record.value()).contains("\"amountReceived\": 120.00");
    }

    private static String header(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        assertThat(header.value()).as("header %s value", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
