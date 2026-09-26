package com.marvel.hospitality.reservation.infrastructure.kafka;

import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.BANK_DLT;
import static com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration.BANK_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.reservation.KafkaTestcontainersConfiguration;
import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentCommand;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentUseCase;
import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.RefundPolicy;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.domain.CancellationReason;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.RefundDue;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bank-transfer payment consumer end to end against a real broker and database: a test {@code KafkaTemplate}
 * produces exactly what the payment service's outbox publishes, the listener applies it, and the tests read the
 * effects from the tables and the outbox (the Debezium leg is covered by the CDC tests). Every ADR-0009 outcome and
 * every ADR-0008 failure path has a test here.
 *
 * <p>Spies on the use case (a plain object, so the spy wraps the real thing) count attempts and inject failures; the
 * refund port is spied to see what would be refunded. Retry waits are milliseconds (test profile), the number of
 * attempts is the production one. Each test books its own stay and uses fresh payment ids, so nothing is cleaned up
 * and the shared database needs no truncation. No sleeps: every wait is an Awaitility condition.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class BankTransferPaymentConsumerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Stays in 2035, three nights each, a week apart: no other test class books that year. */
    private static final LocalDate FIRST_STAY = LocalDate.parse("2035-01-01");
    private static final AtomicInteger STAYS = new AtomicInteger();

    @MockitoSpyBean
    ApplyBankPaymentUseCase applyBankPayment;

    @MockitoSpyBean
    RefundPolicy refundPolicy;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CreateReservationUseCase createReservation;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    /**
     * The three consumer threads join the group one after another, and each join rebalances. A retry sequence
     * interrupted by a rebalance restarts its attempt count on the new owner, so attempt-counting tests would be
     * flaky. Wait until all three partitions are assigned first.
     */
    @BeforeEach
    void allPartitionsAreAssigned() {
        MessageListenerContainer container =
                listenerRegistry.getListenerContainer(BankTransferPaymentUpdateListener.LISTENER_ID);
        await().atMost(TIMEOUT).until(() -> container.getAssignedPartitions() != null
                && container.getAssignedPartitions().size() == 3);
    }

    @Test
    void fullPaymentConfirmsReservationAndEmitsStatusEvent() {
        String reservationId = bankTransferReservation();
        double matchedFullBefore = matchedCount(PaymentMatchOutcome.MATCHED_FULL);
        String paymentId = pay("240.00", "1401541457 " + reservationId);

        awaitPayment(paymentId);

        assertThat(payment(paymentId)).containsEntry("outcome", "MATCHED_FULL")
                .containsEntry("reservation_id", reservationId)
                .containsEntry("property_id", "AMS01")
                .containsEntry("e2e_id", "1401541457")
                .containsEntry("amount", new BigDecimal("240.00"));
        assertThat(reservation(reservationId)).containsEntry("status", "CONFIRMED")
                .containsEntry("amount_received", new BigDecimal("240.00"));
        JsonNode confirmed = lastStatusEvent(reservationId);
        assertThat(confirmed.get("previousStatus").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("reason").asString()).isEqualTo("PAYMENT_RECEIVED");
        assertThat(confirmed.get("amountReceived").decimalValue()).isEqualByComparingTo("240.00");
        assertThat(matchedCount(PaymentMatchOutcome.MATCHED_FULL)).isEqualTo(matchedFullBefore + 1);
        verify(refundPolicy, never()).refundDue(any(), any());
    }

    @ParameterizedTest(name = "reversed = {0}")
    @ValueSource(booleans = {false, true})
    void twoPartialPaymentsConfirmOnSecond(boolean reversed) {
        String reservationId = bankTransferReservation();
        String first = reversed ? "140.00" : "100.00";
        String second = reversed ? "100.00" : "140.00";

        String firstPayment = pay(first, "E2E0000001 " + reservationId);
        awaitPayment(firstPayment);
        assertThat(payment(firstPayment)).containsEntry("outcome", "MATCHED_PARTIAL");
        assertThat(reservation(reservationId)).containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("amount_received", new BigDecimal(first));
        JsonNode partial = lastStatusEvent(reservationId);
        assertThat(partial.get("previousStatus").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(partial.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(partial.get("reason").asString()).isEqualTo("PARTIAL_PAYMENT_RECEIVED");
        assertThat(partial.get("amountReceived").decimalValue()).isEqualByComparingTo(first);

        String secondPayment = pay(second, "E2E0000002 " + reservationId);
        awaitPayment(secondPayment);
        assertThat(payment(secondPayment)).containsEntry("outcome", "MATCHED_FULL");
        assertThat(reservation(reservationId)).containsEntry("status", "CONFIRMED")
                .containsEntry("amount_received", new BigDecimal("240.00"));
        assertThat(statusEventReasons(reservationId))
                .containsExactly("null", "PARTIAL_PAYMENT_RECEIVED", "PAYMENT_RECEIVED");
    }

    @Test
    void duplicatePaymentIdIsAcknowledgedAndAppliedOnce() {
        String reservationId = bankTransferReservation();
        String paymentId = UUID.randomUUID().toString();
        String value = paymentJson(paymentId, "100.00", "1401541457 " + reservationId);

        send(paymentId, value);
        send(paymentId, value);

        await().atMost(TIMEOUT).until(() -> invocations(paymentId) == 2);
        assertThat(jdbc.sql("SELECT count(*) FROM received_payment WHERE payment_id = :id").param("id", paymentId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id").param("id", paymentId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(reservation(reservationId)).containsEntry("amount_received", new BigDecimal("100.00"));
        assertThat(statusEventReasons(reservationId)).containsExactly("null", "PARTIAL_PAYMENT_RECEIVED");
    }

    @Test
    void unmatchedFormatIsStoredWithoutReservation() {
        String paymentId = pay("75.00", "thanks for the lovely room");

        awaitPayment(paymentId);

        assertThat(payment(paymentId)).containsEntry("outcome", "UNMATCHED_FORMAT")
                .containsEntry("reservation_id", null)
                .containsEntry("property_id", null)
                .containsEntry("e2e_id", null)
                .containsEntry("transaction_description", "thanks for the lovely room");
        verify(refundPolicy, never()).refundDue(any(), any());
    }

    @Test
    void unknownReservationIsStored() {
        String unknown = new ReservationIdGenerator().next().value();
        String paymentId = pay("240.00", "1401541457 " + unknown);

        awaitPayment(paymentId);

        assertThat(payment(paymentId)).containsEntry("outcome", "UNMATCHED_UNKNOWN_RESERVATION")
                .containsEntry("reservation_id", null)
                .containsEntry("property_id", null)
                .containsEntry("e2e_id", "1401541457");
        verify(refundPolicy, never()).refundDue(any(), any());
    }

    @Test
    void paymentForCancelledReservationIsStoredAsNotPending() {
        String reservationId = bankTransferReservation();
        cancel(reservationId);

        String paymentId = pay("240.00", "1401541457 " + reservationId);
        awaitPayment(paymentId);

        assertThat(payment(paymentId)).containsEntry("outcome", "UNMATCHED_NOT_PENDING")
                .containsEntry("reservation_id", reservationId)
                .containsEntry("property_id", "AMS01");
        assertThat(reservation(reservationId)).containsEntry("status", "CANCELLED")
                .containsEntry("amount_received", new BigDecimal("0.00"));
        verify(refundPolicy).refundDue(paymentWithId(paymentId),
                eq(new RefundDue(Money.eur("240.00"), RefundReason.RESERVATION_CANCELLED)));
    }

    @Test
    void paymentForConfirmedReservationRequestsOverpaymentRefund() {
        String reservationId = cashReservation();

        String paymentId = pay("240.00", "1401541457 " + reservationId);
        awaitPayment(paymentId);

        assertThat(payment(paymentId)).containsEntry("outcome", "UNMATCHED_NOT_PENDING");
        assertThat(reservation(reservationId)).containsEntry("status", "CONFIRMED")
                .containsEntry("amount_received", new BigDecimal("0.00"));
        verify(refundPolicy).refundDue(paymentWithId(paymentId),
                eq(new RefundDue(Money.eur("240.00"), RefundReason.OVERPAYMENT)));
    }

    @Test
    void overpaymentConfirmsAndRequestsSurplusRefund() {
        String reservationId = bankTransferReservation();

        String paymentId = pay("250.00", "1401541457 " + reservationId);
        awaitPayment(paymentId);

        assertThat(payment(paymentId)).containsEntry("outcome", "OVERPAID");
        assertThat(reservation(reservationId)).containsEntry("status", "CONFIRMED")
                .containsEntry("amount_received", new BigDecimal("250.00"));
        assertThat(lastStatusEvent(reservationId).get("reason").asString()).isEqualTo("PAYMENT_RECEIVED");
        verify(refundPolicy).refundDue(paymentWithId(paymentId),
                eq(new RefundDue(Money.eur("10.00"), RefundReason.OVERPAYMENT)));
    }

    @Test
    void malformedJsonGoesToDltWithoutRetries() {
        String key = UUID.randomUUID().toString();
        send(key, "{\"paymentId\": \"" + key + "\", \"amountReceived\": ");

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(BANK_DLT, key, TIMEOUT);

        assertThat(dead.value()).startsWith("{\"paymentId\"");
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotBlank();
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(BANK_TOPIC);
        verify(applyBankPayment, never()).apply(any());
    }

    @Test
    void invalidPaymentGoesToDltWithoutRetries() {
        String paymentId = UUID.randomUUID().toString();
        send(paymentId, paymentJson(paymentId, "-5.00", "1401541457 P4145478"));

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(BANK_DLT, paymentId, TIMEOUT);

        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("MethodArgumentNotValidException");
        verify(applyBankPayment, never()).apply(any());
    }

    @Test
    void transientDatabaseErrorIsRetriedThenSucceeds() {
        String reservationId = bankTransferReservation();
        String paymentId = UUID.randomUUID().toString();
        doThrow(new TransientDataAccessResourceException("injected: connection reset"))
                .doThrow(new TransientDataAccessResourceException("injected: connection reset"))
                .doCallRealMethod()
                .when(applyBankPayment).apply(commandFor(paymentId));

        send(paymentId, paymentJson(paymentId, "240.00", "1401541457 " + reservationId));
        awaitPayment(paymentId);

        assertThat(invocations(paymentId)).isEqualTo(3);
        assertThat(reservation(reservationId)).containsEntry("status", "CONFIRMED");
    }

    @Test
    void exhaustedRetriesSendToDlt() {
        String reservationId = bankTransferReservation();
        String paymentId = UUID.randomUUID().toString();
        doThrow(new TransientDataAccessResourceException("injected: database down"))
                .when(applyBankPayment).apply(commandFor(paymentId));

        send(paymentId, paymentJson(paymentId, "240.00", "1401541457 " + reservationId));
        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(BANK_DLT, paymentId, TIMEOUT);

        assertThat(invocations(paymentId)).isEqualTo(5);
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo(TransientDataAccessResourceException.class.getName());
        assertThat(jdbc.sql("SELECT count(*) FROM received_payment WHERE payment_id = :id").param("id", paymentId)
                .query(Long.class).single()).isZero();
        assertThat(reservation(reservationId)).containsEntry("status", "PENDING_PAYMENT");
    }

    @Test
    void nextMessageOnSamePartitionIsProcessedAfterPoisonMessageIsDeadLettered() {
        String reservationId = bankTransferReservation();
        String poison = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        doThrow(new TransientDataAccessResourceException("injected: poison"))
                .when(applyBankPayment).apply(commandFor(poison));

        send(0, poison, paymentJson(poison, "100.00", "1401541457 " + reservationId));
        send(0, next, paymentJson(next, "240.00", "1401541457 " + reservationId));

        DebeziumCdc.awaitRecord(BANK_DLT, poison, TIMEOUT);
        awaitPayment(next);
        InOrder inOrder = inOrder(applyBankPayment);
        inOrder.verify(applyBankPayment, times(5)).apply(commandFor(poison));
        inOrder.verify(applyBankPayment).apply(commandFor(next));
        assertThat(reservation(reservationId)).containsEntry("status", "CONFIRMED");
    }

    /**
     * Two payments for one reservation have different keys ({@code paymentId}), so they can be consumed at the same
     * time by two consumer threads; they are sent to two partitions to make that likely. The row lock serialises
     * them: both count, the sum is right, and neither needed a retry (an optimistic-lock failure would have shown up
     * as a third invocation).
     */
    @Test
    void concurrentPaymentsForSameReservationSerialise() {
        String reservationId = bankTransferReservation();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        send(0, first, paymentJson(first, "120.00", "E2E0000001 " + reservationId));
        send(1, second, paymentJson(second, "120.00", "E2E0000002 " + reservationId));

        awaitPayment(first);
        awaitPayment(second);
        await().atMost(TIMEOUT).until(() -> "CONFIRMED".equals(reservation(reservationId).get("status")));
        assertThat(reservation(reservationId)).containsEntry("amount_received", new BigDecimal("240.00"));
        assertThat(List.of(payment(first).get("outcome"), payment(second).get("outcome")))
                .containsExactlyInAnyOrder("MATCHED_PARTIAL", "MATCHED_FULL");
        assertThat(invocations(first) + invocations(second)).isEqualTo(2);
        assertThat(statusEventReasons(reservationId))
                .containsExactly("null", "PARTIAL_PAYMENT_RECEIVED", "PAYMENT_RECEIVED");
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    /** AMS01 room 101 (SMALL, 80/night), three nights = 240.00, bank transfer, a stay of its own. */
    private String bankTransferReservation() {
        return book(PaymentMode.BANK_TRANSFER);
    }

    private String cashReservation() {
        return book(PaymentMode.CASH);
    }

    private String book(PaymentMode mode) {
        LocalDate start = FIRST_STAY.plusWeeks(STAYS.getAndIncrement());
        return createReservation.create(new CreateReservationCommand("AMS01", "Ada Lovelace", "101", start,
                        start.plusDays(3), RoomSegment.SMALL, mode, null))
                .reservation().reservationId().value();
    }

    /** What the auto-cancel job will do; done directly here because the job is not what is under test. */
    private void cancel(String reservationId) {
        transactions.executeWithoutResult(status -> {
            Reservation reservation = reservations.findForUpdate(
                    ReservationId.of(reservationId)).orElseThrow();
            reservation.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, clock);
            reservations.update(reservation);
        });
    }

    private String pay(String amount, String description) {
        String paymentId = UUID.randomUUID().toString();
        send(paymentId, paymentJson(paymentId, amount, description));
        return paymentId;
    }

    /** Exactly the payment service's {@code PaymentReceivedPayload}, field names verbatim from the brief. */
    private String paymentJson(String paymentId, String amount, String description) {
        return jsonMapper.writeValueAsString(Map.of(
                "paymentId", paymentId,
                "debtorAccountnumber", "NL91ABNA0417164300",
                "amountReceived", new BigDecimal(amount),
                "transactionDescription", description));
    }

    private void send(String key, String value) {
        join(kafkaTemplate.send(BANK_TOPIC, key, value));
    }

    private void send(int partition, String key, String value) {
        join(kafkaTemplate.send(BANK_TOPIC, partition, key, value));
    }

    private static void join(CompletableFuture<?> sent) {
        try {
            sent.get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test record", e);
        }
    }

    private void awaitPayment(String paymentId) {
        await().atMost(TIMEOUT).until(() -> jdbc.sql("SELECT count(*) FROM received_payment WHERE payment_id = :id")
                .param("id", paymentId).query(Long.class).single() == 1);
    }

    private Map<String, Object> payment(String paymentId) {
        return jdbc.sql("SELECT * FROM received_payment WHERE payment_id = :id").param("id", paymentId)
                .query().singleRow();
    }

    private Map<String, Object> reservation(String reservationId) {
        return jdbc.sql("SELECT status, amount_received FROM reservation WHERE reservation_id = :id")
                .param("id", reservationId).query().singleRow();
    }

    private List<JsonNode> statusEvents(String reservationId) {
        return jdbc.sql("""
                        SELECT payload::text FROM outbox_event
                         WHERE aggregate_id = :id AND event_type = 'ReservationStatusChanged'
                         ORDER BY created_at, (payload->>'amountReceived')::numeric
                        """)
                .param("id", reservationId).query(String.class).list().stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    private List<String> statusEventReasons(String reservationId) {
        return statusEvents(reservationId).stream()
                .map(event -> event.get("reason").isNull() ? "null" : event.get("reason").asString())
                .toList();
    }

    private JsonNode lastStatusEvent(String reservationId) {
        List<JsonNode> events = statusEvents(reservationId);
        return events.getLast();
    }

    private double matchedCount(PaymentMatchOutcome outcome) {
        Counter counter = meterRegistry.find(BankTransferPaymentUpdateListener.MATCHED_METRIC)
                .tag("outcome", outcome.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    private long invocations(String paymentId) {
        return mockingDetails(applyBankPayment).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("apply"))
                .filter(invocation -> ((ApplyBankPaymentCommand) invocation.getArgument(0)).paymentId().equals(paymentId))
                .count();
    }

    private static ApplyBankPaymentCommand commandFor(String paymentId) {
        return argThat(command -> command != null && command.paymentId().equals(paymentId));
    }

    private static ReceivedPayment paymentWithId(String paymentId) {
        return argThat(payment -> payment != null && payment.paymentId().equals(paymentId));
    }

    private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
