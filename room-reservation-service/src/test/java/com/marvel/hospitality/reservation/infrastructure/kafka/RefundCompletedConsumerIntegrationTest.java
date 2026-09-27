package com.marvel.hospitality.reservation.infrastructure.kafka;

import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.REFUND_COMPLETED_DLT;
import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.REFUND_COMPLETED_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.reservation.KafkaListenersIntegrationTest;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentCommand;
import com.marvel.hospitality.reservation.application.CompleteRefundCommand;
import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code refund-completed} consumer end to end against a real broker and database (saga step 6 of ADR-0006). Each
 * test first requests a refund the way production does — an overpayment applied through the real use case — and then
 * sends exactly what the payment service's outbox publishes for it.
 *
 * <p>Runs in the one listening context of {@link KafkaListenersIntegrationTest}. Stays are in 2038, room 102, a week
 * apart: no other test class books them.
 */
class RefundCompletedConsumerIntegrationTest extends KafkaListenersIntegrationTest {

    private static final LocalDate FIRST_STAY = LocalDate.parse("2038-01-04");
    private static final AtomicInteger STAYS = new AtomicInteger();
    private static final Instant COMPLETED_AT = Instant.parse("2038-01-01T09:16:05Z");

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CreateReservationUseCase createReservation;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void refundCompletedMarksRefundCompleted() {
        RequestedRefund refund = requestedRefund();

        send(refund.paymentId(), completion(refund, "COMPLETED", null));

        await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(refundRow(refund).get("status")));
        assertThat(refundRow(refund)).containsEntry("failure_reason", null)
                .containsEntry("completed_at", Timestamp.from(COMPLETED_AT))
                .containsEntry("amount", new BigDecimal("10.00"));
    }

    @Test
    void refundFailedMarksRefundFailedAndCountsMetric() {
        RequestedRefund refund = requestedRefund();
        double failedBefore = failedRefunds();

        send(refund.paymentId(), completion(refund, "FAILED", "CREDITOR_ACCOUNT_REJECTED"));

        await().atMost(TIMEOUT).until(() -> "FAILED".equals(refundRow(refund).get("status")));
        assertThat(refundRow(refund)).containsEntry("failure_reason", "CREDITOR_ACCOUNT_REJECTED")
                .containsEntry("completed_at", Timestamp.from(COMPLETED_AT));
        await().atMost(TIMEOUT).until(() -> failedRefunds() == failedBefore + 1);
    }

    /** The redelivery is acknowledged and changes nothing, and a failure is counted once, not per delivery. */
    @Test
    void duplicateRefundCompletedIsIgnored() {
        RequestedRefund refund = requestedRefund();
        double failedBefore = failedRefunds();
        String value = completion(refund, "FAILED", "CREDITOR_ACCOUNT_REJECTED");

        send(refund.paymentId(), value);
        send(refund.paymentId(), value);

        await().atMost(TIMEOUT).until(() -> invocations(refund.refundId()) == 2);
        assertThat(refundRow(refund)).containsEntry("status", "FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id")
                .param("id", refund.refundId().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(failedRefunds()).isEqualTo(failedBefore + 1);
    }

    /** A {@code refundId} this service never requested is a contract violation (ADR-0008): DLT, no retries. */
    @Test
    void unknownRefundGoesToDltWithoutRetries() {
        RequestedRefund unknown = new RequestedRefund(UUID.randomUUID(), UUID.randomUUID().toString());

        send(unknown.paymentId(), completion(unknown, "COMPLETED", null));
        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(REFUND_COMPLETED_DLT, unknown.paymentId(), TIMEOUT);

        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(invocations(unknown.refundId())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id")
                .param("id", unknown.refundId().toString()).query(Long.class).single()).isZero();
    }

    @Test
    void completionNamingAnotherPaymentGoesToDltAndLeavesRefundRequested() {
        RequestedRefund refund = requestedRefund();
        RequestedRefund wrongPayment = new RequestedRefund(refund.refundId(), UUID.randomUUID().toString());

        send(wrongPayment.paymentId(), completion(wrongPayment, "COMPLETED", null));
        DebeziumCdc.awaitRecord(REFUND_COMPLETED_DLT, wrongPayment.paymentId(), TIMEOUT);

        assertThat(invocations(refund.refundId())).isEqualTo(1);
        assertThat(refundRow(refund)).containsEntry("status", "REQUESTED").containsEntry("completed_at", null);
    }

    @Test
    void invalidRefundCompletedGoesToDltWithoutRetries() {
        RequestedRefund refund = requestedRefund();

        send(refund.paymentId(), completion(refund, "DONE", null));
        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(REFUND_COMPLETED_DLT, refund.paymentId(), TIMEOUT);

        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("MethodArgumentNotValidException");
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(REFUND_COMPLETED_TOPIC);
        verify(completeRefund, never()).complete(any());
        assertThat(refundRow(refund)).containsEntry("status", "REQUESTED");
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private record RequestedRefund(UUID refundId, String paymentId) {
    }

    /** A bank-transfer booking of 240.00 overpaid with 250.00: a {@code REQUESTED} refund of 10.00. */
    private RequestedRefund requestedRefund() {
        LocalDate start = FIRST_STAY.plusWeeks(STAYS.getAndIncrement());
        String reservationId = createReservation.create(new CreateReservationCommand("AMS01", "Ada Lovelace", "102",
                        start, start.plusDays(3), RoomSegment.SMALL, PaymentMode.BANK_TRANSFER, null))
                .reservation().reservationId().value();
        String paymentId = UUID.randomUUID().toString();
        applyBankPayment.apply(new ApplyBankPaymentCommand(
                paymentId, "NL91ABNA0417164300", Money.eur("250.00"), "1401541457 " + reservationId));
        UUID refundId = jdbc.sql("SELECT refund_id FROM refund WHERE payment_id = :id").param("id", paymentId)
                .query(UUID.class).single();
        return new RequestedRefund(refundId, paymentId);
    }

    /** Exactly the payment service's {@code RefundCompletedPayload} (events.md). */
    private String completion(RequestedRefund refund, String status, @Nullable String failureReason) {
        Map<String, Object> value = new HashMap<>();
        value.put("refundId", refund.refundId().toString());
        value.put("paymentId", refund.paymentId());
        value.put("reservationId", "P4145478");
        value.put("propertyId", "AMS01");
        value.put("amount", new BigDecimal("10.00"));
        value.put("currency", "EUR");
        value.put("status", status);
        value.put("failureReason", failureReason);
        value.put("completedAt", COMPLETED_AT.toString());
        return jsonMapper.writeValueAsString(value);
    }

    private void send(String key, String value) {
        try {
            kafkaTemplate.send(REFUND_COMPLETED_TOPIC, key, value).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test record", e);
        }
    }

    private Map<String, Object> refundRow(RequestedRefund refund) {
        return jdbc.sql("SELECT * FROM refund WHERE refund_id = :id").param("id", refund.refundId())
                .query().singleRow();
    }

    private double failedRefunds() {
        return meterRegistry.get(RefundCompletedListener.FAILED_METRIC).counter().count();
    }

    private long invocations(UUID refundId) {
        return mockingDetails(completeRefund).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("complete"))
                .filter(invocation -> ((CompleteRefundCommand) invocation.getArgument(0)).refundId().equals(refundId))
                .count();
    }

    private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
