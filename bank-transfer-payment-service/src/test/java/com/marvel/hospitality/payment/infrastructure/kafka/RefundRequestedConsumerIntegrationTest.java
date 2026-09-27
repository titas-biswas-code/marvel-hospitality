package com.marvel.hospitality.payment.infrastructure.kafka;

import static com.marvel.hospitality.payment.KafkaTestcontainersConfiguration.REFUND_REQUESTED_DLT;
import static com.marvel.hospitality.payment.KafkaTestcontainersConfiguration.REFUND_REQUESTED_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.marvel.hospitality.payment.KafkaListenersIntegrationTest;
import com.marvel.hospitality.payment.application.ExecuteRefundCommand;
import com.marvel.hospitality.payment.application.ExecuteRefundUseCase;
import com.marvel.hospitality.payment.application.IngestBankTransactionCommand;
import com.marvel.hospitality.payment.application.IngestBankTransactionUseCase;
import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refund-requested consumer end to end against a real broker and database (ADR-0014, contracts/events.md): a
 * test {@code KafkaTemplate} produces exactly what the reservation service's outbox publishes, the listener applies
 * it, and the tests read the effects straight off {@code refund_instruction} and {@code outbox_event} (the Debezium
 * leg of {@code RefundCompleted} is covered by {@link
 * com.marvel.hospitality.payment.infrastructure.outbox.RefundCompletedCdcTest}). Every ADR-0014 outcome and the
 * ADR-0008 poison-message path have a test here.
 *
 * <p>The {@link KafkaListenersIntegrationTest} spy on {@link ExecuteRefundUseCase} counts attempts, the same way
 * {@code BankTransferPaymentConsumerIntegrationTest} in room-reservation-service does. Retry waits are
 * milliseconds (test profile). Each test uses a fresh {@code refundId}/{@code paymentId}, so nothing is cleaned up
 * and the shared database needs no truncation. No sleeps: every wait is an Awaitility condition.
 */
class RefundRequestedConsumerIntegrationTest extends KafkaListenersIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    IngestBankTransactionUseCase ingest;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void refundRequestedCreatesInstructionToOriginalDebtorAndEmitsCompleted() {
        String paymentId = ingestTransaction("NL91ABNA0417164300", "120.00");
        String refundId = UUID.randomUUID().toString();

        send(paymentId, refundRequestJson(refundId, paymentId, "30.00", "OVERPAYMENT"));
        awaitInstruction(refundId);

        assertThat(instruction(refundId)).containsEntry("status", "EXECUTED")
                .containsEntry("creditor_account_number", "NL91ABNA0417164300")
                .containsEntry("payment_id", paymentId)
                .containsEntry("amount", new BigDecimal("30.00"));

        assertThat(outboxCountForPaymentId(paymentId)).isEqualTo(1);
        Map<String, Object> outboxRow = outboxRowForPaymentId(paymentId);
        assertThat(outboxRow).containsEntry("topic", "refund-completed")
                .containsEntry("event_type", "RefundCompleted")
                .containsEntry("aggregate_id", paymentId)
                .containsEntry("property_id", "AMS01")
                .containsEntry("payload_status", "COMPLETED");
        assertThat(outboxRow.get("payload_failure_reason")).isNull();
    }

    @Test
    void duplicateRefundRequestedIsIgnored() {
        String paymentId = ingestTransaction("NL91ABNA0417164300", "50.00");
        String refundId = UUID.randomUUID().toString();
        String value = refundRequestJson(refundId, paymentId, "20.00", "OVERPAYMENT");

        send(paymentId, value);
        awaitInstruction(refundId);
        send(paymentId, value);

        await().atMost(TIMEOUT).until(() -> invocations(refundId) == 2);
        assertThat(jdbc.sql("SELECT count(*) FROM refund_instruction WHERE refund_id = :id")
                .param("id", UUID.fromString(refundId)).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id").param("id", refundId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(outboxCountForPaymentId(paymentId)).isEqualTo(1);
    }

    @Test
    void failingAccountEmitsFailedCompletion() {
        String paymentId = ingestTransaction("FAILACCOUNT00000001", "80.00");
        String refundId = UUID.randomUUID().toString();

        send(paymentId, refundRequestJson(refundId, paymentId, "80.00", "RESERVATION_CANCELLED"));
        awaitInstruction(refundId);

        assertThat(instruction(refundId)).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "CREDITOR_ACCOUNT_REJECTED");

        Map<String, Object> outboxRow = outboxRowForPaymentId(paymentId);
        assertThat(outboxRow.get("payload_status")).isEqualTo("FAILED");
        assertThat(outboxRow.get("payload_failure_reason")).isEqualTo("CREDITOR_ACCOUNT_REJECTED");
    }

    @Test
    void unknownPaymentEmitsFailedCompletionWithReason() {
        String paymentId = UUID.randomUUID().toString();
        String refundId = UUID.randomUUID().toString();

        send(paymentId, refundRequestJson(refundId, paymentId, "10.00", "OVERPAYMENT"));

        await().atMost(TIMEOUT).until(() -> outboxCountForPaymentId(paymentId) == 1);
        assertThat(jdbc.sql("SELECT count(*) FROM refund_instruction WHERE refund_id = :id")
                .param("id", UUID.fromString(refundId)).query(Long.class).single()).isZero();

        Map<String, Object> outboxRow = outboxRowForPaymentId(paymentId);
        assertThat(outboxRow.get("payload_status")).isEqualTo("FAILED");
        assertThat(outboxRow.get("payload_failure_reason")).isEqualTo("UNKNOWN_PAYMENT");
    }

    @Test
    void refundLargerThanPaymentIsFailedWithoutExecuting() {
        String paymentId = ingestTransaction("NL91ABNA0417164300", "40.00");
        String refundId = UUID.randomUUID().toString();

        send(paymentId, refundRequestJson(refundId, paymentId, "999.00", "OVERPAYMENT"));
        awaitInstruction(refundId);

        assertThat(instruction(refundId)).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "AMOUNT_EXCEEDS_PAYMENT");
        Map<String, Object> outboxRow = outboxRowForPaymentId(paymentId);
        assertThat(outboxRow.get("payload_status")).isEqualTo("FAILED");
        assertThat(outboxRow.get("payload_failure_reason")).isEqualTo("AMOUNT_EXCEEDS_PAYMENT");
    }

    @Test
    void invalidRefundRequestedGoesToDltWithoutRetries() {
        String paymentId = UUID.randomUUID().toString();
        String refundId = UUID.randomUUID().toString();

        send(paymentId, refundRequestJson(refundId, paymentId, "-5.00", "OVERPAYMENT"));

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(REFUND_REQUESTED_DLT, paymentId, TIMEOUT);

        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("MethodArgumentNotValidException");
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(REFUND_REQUESTED_TOPIC);
        verify(executeRefund, never()).execute(any());
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private String ingestTransaction(String debtorAccountNumber, String amount) {
        String ref = "BANK-TX-REFUND-" + UUID.randomUUID();
        return ingest.ingest(new IngestBankTransactionCommand(ref, debtorAccountNumber, "A. Lovelace",
                        new BigDecimal(amount), "EUR", "refund consumer test", Instant.parse("2026-10-01T09:15:00Z"),
                        "{\"bankTransactionRef\":\"" + ref + "\"}"))
                .transaction().paymentId().toString();
    }

    private String refundRequestJson(String refundId, String paymentId, String amount, String reason) {
        return jsonMapper.writeValueAsString(Map.of(
                "refundId", refundId,
                "paymentId", paymentId,
                "reservationId", "P4145478",
                "propertyId", "AMS01",
                "amount", new BigDecimal(amount),
                "currency", "EUR",
                "reason", reason,
                "requestedAt", "2026-10-01T09:16:00Z"));
    }

    private void send(String key, String value) {
        join(kafkaTemplate.send(REFUND_REQUESTED_TOPIC, key, value));
    }

    private static void join(CompletableFuture<?> sent) {
        try {
            sent.get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test record", e);
        }
    }

    private void awaitInstruction(String refundId) {
        await().atMost(TIMEOUT).until(() -> jdbc.sql("SELECT count(*) FROM refund_instruction WHERE refund_id = :id")
                .param("id", UUID.fromString(refundId)).query(Long.class).single() == 1);
    }

    private Map<String, Object> instruction(String refundId) {
        return jdbc.sql("""
                        SELECT status, creditor_account_number, failure_reason, amount, payment_id::text AS payment_id
                          FROM refund_instruction WHERE refund_id = :id
                        """)
                .param("id", UUID.fromString(refundId)).query().singleRow();
    }

    private long outboxCountForPaymentId(String paymentId) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_type = 'refund' AND aggregate_id = :id")
                .param("id", paymentId).query(Long.class).single();
    }

    private Map<String, Object> outboxRowForPaymentId(String paymentId) {
        return jdbc.sql("""
                        SELECT aggregate_type, aggregate_id, event_type, topic, property_id,
                               payload ->> 'status' AS payload_status,
                               payload ->> 'failureReason' AS payload_failure_reason
                          FROM outbox_event
                         WHERE aggregate_type = 'refund' AND aggregate_id = :id
                        """)
                .param("id", paymentId).query().singleRow();
    }

    private long invocations(String refundId) {
        return mockingDetails(executeRefund).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .filter(invocation -> ((ExecuteRefundCommand) invocation.getArgument(0)).refundId().toString().equals(refundId))
                .count();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        assertThat(header.value()).as("header %s value", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
