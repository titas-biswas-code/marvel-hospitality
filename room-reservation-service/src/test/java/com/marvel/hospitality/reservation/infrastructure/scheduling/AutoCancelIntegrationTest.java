package com.marvel.hospitality.reservation.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.MutableClock;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentCommand;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentResult;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentUseCase;
import com.marvel.hospitality.reservation.application.CancelOverdueReservationUseCase;
import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.OutboxWriter;
import com.marvel.hospitality.reservation.application.PropertyCatalog;
import com.marvel.hospitality.reservation.application.RefundPolicy;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.application.ReservationView;
import com.marvel.hospitality.reservation.domain.CreditCardPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.NewReservation;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.RefundDue;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0010's auto-cancel job against real Postgres: {@link AutoCancelJob#run()} is called directly in every test, so
 * nothing here waits on the real {@code @Scheduled} trigger — {@code initial-delay=PT1H} on top of that guarantees it
 * cannot fire during a test run anyway. Time moves by setting a {@link MutableClock} bean (this context's
 * {@code @Primary} replacement for the production {@code Clock.systemUTC()}), never by sleeping. No Kafka: the job's
 * only externally visible effects are the {@code reservation} row and the {@code outbox_event} row, both asserted
 * directly against the tables; Debezium's leg of getting that row onto a topic is covered by the CDC tests.
 *
 * <p><b>Shared-database caveat.</b> Like every other {@code @SpringBootTest} in this service, this context runs
 * against the one Postgres container shared JVM-wide (see {@link TestcontainersConfiguration}). Moving this test's
 * clock forward to make its own fixtures due also makes <em>any other test class's leftover
 * {@code PENDING_PAYMENT} bank-transfer row</em> due, and a {@link AutoCancelJob#run()} call cancels those too —
 * there is no way to run this job against only "this test's rows". Every assertion below is therefore scoped to
 * this test's own reservation ids, never to a total row count; and {@link #twoConcurrentJobRunsDoNotCancelTheSameRowTwice()}
 * (which does compare a tally to its own row count) first "drains" the currently-due backlog with one run at a
 * clock far in the future, then rewinds the clock to create its own fixtures, so nothing outside this test can still
 * be due by the time it counts.
 *
 * <p>Fixtures live in 2037 (no other test class in this service books that year) and each call to {@link #book} or
 * {@link #creditCardReservation()} claims the next slot from a shared, ever-increasing sequence of (room, week)
 * pairs, so no two reservations created anywhere in this class — including the 40 the concurrency test needs — ever
 * overlap the {@code reservation_no_overlap} exclusion constraint.
 */
@SpringBootTest(properties = {"reservation.auto-cancel.enabled=true", "reservation.auto-cancel.initial-delay=PT1H"})
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class,
        AutoCancelIntegrationTest.MutableClockConfiguration.class})
@ActiveProfiles("test")
class AutoCancelIntegrationTest {

    private static final String PROPERTY_ID = "AMS01";
    private static final String[] ROOMS = {"101", "102", "201", "202", "301", "401"};
    private static final Map<String, RoomSegment> SEGMENT_BY_ROOM = Map.of(
            "101", RoomSegment.SMALL, "102", RoomSegment.SMALL,
            "201", RoomSegment.MEDIUM, "202", RoomSegment.MEDIUM,
            "301", RoomSegment.LARGE, "401", RoomSegment.EXTRA_LARGE);
    /** Every call to {@link #nextSlot()} gets its own week, so start dates only ever increase. */
    private static final LocalDate FIRST_STAY = LocalDate.parse("2037-01-05");
    /** Well before any deadline a stay in 2037 can have; the clock is set here before every new reservation. */
    private static final Instant BOOKING_INSTANT = Instant.parse("2036-06-01T10:00:00Z");
    /** Past every deadline any fixture in this class (or left over from another class) can have. */
    private static final Instant FAR_FUTURE = Instant.parse("2039-01-01T00:00:00Z");
    private static final int CONCURRENT_ROWS = 40;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @MockitoSpyBean
    CancelOverdueReservationUseCase cancelOverdue;

    @MockitoSpyBean
    RefundPolicy refundPolicy;

    @Autowired
    AutoCancelJob job;

    @Autowired
    CreateReservationUseCase createReservation;

    @Autowired
    ApplyBankPaymentUseCase applyBankPayment;

    @Autowired
    PropertyCatalog catalog;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void cancelsOnlyBankTransferReservationsPastDeadline() {
        String cash = book(PaymentMode.CASH).reservation().reservationId().value();
        ReservationView overdueView = book(PaymentMode.BANK_TRANSFER);
        String overdue = overdueView.reservation().reservationId().value();
        // nextSlot() only ever moves start dates forward, so a later book() call always gets a later deadline.
        ReservationView futureView = book(PaymentMode.BANK_TRANSFER);
        String future = futureView.reservation().reservationId().value();
        String creditCard = creditCardReservation().reservationId().value();
        Instant overdueDeadline = overdueView.reservation().paymentDeadlineAt();
        Instant futureDeadline = futureView.reservation().paymentDeadlineAt();
        assertThat(overdueDeadline).isBefore(futureDeadline);

        clock.set(overdueDeadline.plus(Duration.ofHours(1)));
        job.run();

        assertThat(reservationRow(overdue)).containsEntry("status", "CANCELLED")
                .containsEntry("cancellation_reason", "PAYMENT_DEADLINE_MISSED");
        assertThat(reservationRow(future)).containsEntry("status", "PENDING_PAYMENT");
        assertThat(reservationRow(cash)).containsEntry("status", "CONFIRMED");
        assertThat(reservationRow(creditCard)).containsEntry("status", "CONFIRMED");
    }

    @Test
    void doesNotCancelBeforeDeadline() {
        ReservationView view = book(PaymentMode.BANK_TRANSFER);
        String reservationId = view.reservation().reservationId().value();
        clock.set(view.reservation().paymentDeadlineAt().minusMillis(1));

        job.run();

        assertThat(reservationRow(reservationId)).containsEntry("status", "PENDING_PAYMENT");
    }

    @Test
    void cancelsExactlyAtDeadline() {
        ReservationView view = book(PaymentMode.BANK_TRANSFER);
        String reservationId = view.reservation().reservationId().value();
        clock.set(view.reservation().paymentDeadlineAt());

        job.run();

        assertThat(reservationRow(reservationId)).containsEntry("status", "CANCELLED");
    }

    @Test
    void emitsStatusChangedWithDeadlineMissedReason() {
        ReservationView view = book(PaymentMode.BANK_TRANSFER);
        String reservationId = view.reservation().reservationId().value();
        clock.set(view.reservation().paymentDeadlineAt());

        job.run();

        // singleRow() also proves there is exactly one such CANCELLED event for this id: two would fail the query.
        Map<String, Object> event = jdbc.sql("""
                        SELECT topic, event_type, property_id,
                               payload->>'previousStatus' AS previous_status,
                               payload->>'status' AS status,
                               payload->>'reason' AS reason
                          FROM outbox_event
                         WHERE aggregate_id = :id AND event_type = 'ReservationStatusChanged'
                           AND payload->>'status' = 'CANCELLED'
                        """)
                .param("id", reservationId).query().singleRow();
        assertThat(event).containsEntry("topic", "reservation-status-changed")
                .containsEntry("event_type", "ReservationStatusChanged")
                .containsEntry("property_id", PROPERTY_ID)
                .containsEntry("previous_status", "PENDING_PAYMENT")
                .containsEntry("status", "CANCELLED")
                .containsEntry("reason", "PAYMENT_DEADLINE_MISSED");
    }

    /**
     * Two threads call {@link AutoCancelJob#run()} at the same instant against 40 rows that are all due; every row
     * still ends up cancelled exactly once. The per-row claim is {@code SELECT ... FOR UPDATE SKIP LOCKED}
     * (ADR-0010): whichever run's claim loses the race on a given row simply does not see it in its own result set
     * and tallies it as skipped, never as a second cancellation — so {@code runA.cancelled() + runB.cancelled()}
     * accounts for all 40 rows with no overlap.
     */
    @Test
    void twoConcurrentJobRunsDoNotCancelTheSameRowTwice() throws Exception {
        // Drain first (see the class javadoc): whatever is left over from earlier tests becomes due the moment the
        // clock jumps forward, and must be gone before this test creates and counts its own 40 rows.
        clock.set(FAR_FUTURE);
        job.run();
        clock.set(BOOKING_INSTANT);

        List<String> reservationIds = new ArrayList<>();
        Instant latestDeadline = Instant.MIN;
        for (int i = 0; i < CONCURRENT_ROWS; i++) {
            ReservationView view = book(PaymentMode.BANK_TRANSFER);
            reservationIds.add(view.reservation().reservationId().value());
            Instant deadline = view.reservation().paymentDeadlineAt();
            if (deadline.isAfter(latestDeadline)) {
                latestDeadline = deadline;
            }
        }
        clock.set(latestDeadline);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            Callable<AutoCancelRun> runJob = () -> {
                bothReady.countDown();
                bothReady.await();
                return job.run();
            };
            Future<AutoCancelRun> first = executor.submit(runJob);
            Future<AutoCancelRun> second = executor.submit(runJob);
            AutoCancelRun runA = first.get(30, TimeUnit.SECONDS);
            AutoCancelRun runB = second.get(30, TimeUnit.SECONDS);

            assertThat(runA.failed()).isZero();
            assertThat(runB.failed()).isZero();
            assertThat(runA.cancelled() + runB.cancelled()).isEqualTo(CONCURRENT_ROWS);
            for (String reservationId : reservationIds) {
                assertThat(reservationRow(reservationId)).containsEntry("status", "CANCELLED");
                assertThat(cancelledEventCount(reservationId)).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A row that keeps throwing must not roll back the rows around it in the same page ({@code REQUIRES_NEW} per
     * row, ADR-0010). After the failing row is made to behave normally again, the next run finishes the job the
     * first one could not, and the overdue gauge — rows still due after a run — returns to zero.
     */
    @Test
    void oneFailingRowDoesNotRollBackTheOthers() {
        ReservationView first = book(PaymentMode.BANK_TRANSFER);
        ReservationView middle = book(PaymentMode.BANK_TRANSFER);
        ReservationView last = book(PaymentMode.BANK_TRANSFER);
        UUID middleRowId = rowIdFor(middle.reservation().reservationId().value());
        clock.set(last.reservation().paymentDeadlineAt());
        doThrow(new IllegalStateException("boom")).when(cancelOverdue).cancelIfOverdue(eq(middleRowId), any());
        double cancelledBefore = cancelledCounterValue();

        AutoCancelRun run = job.run();

        assertThat(reservationRow(first.reservation().reservationId().value())).containsEntry("status", "CANCELLED");
        assertThat(reservationRow(last.reservation().reservationId().value())).containsEntry("status", "CANCELLED");
        assertThat(reservationRow(middle.reservation().reservationId().value()))
                .containsEntry("status", "PENDING_PAYMENT");
        assertThat(run.failed()).isGreaterThanOrEqualTo(1);
        assertThat(overdueGaugeValue()).isGreaterThanOrEqualTo(1);

        // The row behaves normally again; the next run finds it still due (nobody else touched it) and cancels it.
        doCallRealMethod().when(cancelOverdue).cancelIfOverdue(eq(middleRowId), any());
        job.run();

        assertThat(reservationRow(middle.reservation().reservationId().value())).containsEntry("status", "CANCELLED");
        // A full run with nothing left failing or locked elsewhere cancels every currently-due row, foreign leftover
        // rows included (the shared-database caveat) — so the gauge is exactly zero, not merely small.
        assertThat(overdueGaugeValue()).isEqualTo(0);
        // >= 3, not == 3: a foreign leftover row becoming due at the same instant would also add to this counter.
        assertThat(cancelledCounterValue() - cancelledBefore).isGreaterThanOrEqualTo(3);
    }

    /**
     * Nothing about a due row is kept in memory between runs — the deadline is the whole schedule (ADR-0010) — so a
     * "restart" is simulated simply by never having called {@link AutoCancelJob#run()} while the clock walked past
     * the deadline and on for several more days; the very first run afterwards still finds and cancels the row.
     */
    @Test
    void restartSafe_rowsDueWhileDownAreCancelledOnNextRun() {
        ReservationView view = book(PaymentMode.BANK_TRANSFER);
        String reservationId = view.reservation().reservationId().value();
        clock.set(view.reservation().paymentDeadlineAt().plus(Duration.ofDays(5)));

        job.run();

        assertThat(reservationRow(reservationId)).containsEntry("status", "CANCELLED");
    }

    @Test
    void paymentArrivingAfterCancellationIsStoredAsNotPending() {
        ReservationView view = book(PaymentMode.BANK_TRANSFER);
        String reservationId = view.reservation().reservationId().value();
        Money total = view.reservation().totalAmount();
        clock.set(view.reservation().paymentDeadlineAt());
        job.run();
        assertThat(reservationRow(reservationId)).containsEntry("status", "CANCELLED");

        String paymentId = UUID.randomUUID().toString();
        ApplyBankPaymentResult result = applyBankPayment.apply(new ApplyBankPaymentCommand(
                paymentId, "NL91ABNA0417164300", total, "E2E0000001 " + reservationId));

        assertThat(result.outcome()).isEqualTo(PaymentMatchOutcome.UNMATCHED_NOT_PENDING);
        assertThat(jdbc.sql("SELECT outcome FROM received_payment WHERE payment_id = :id").param("id", paymentId)
                .query(String.class).single()).isEqualTo("UNMATCHED_NOT_PENDING");
        assertThat(reservationRow(reservationId)).containsEntry("status", "CANCELLED");
        verify(refundPolicy).refundDue(paymentWithId(paymentId),
                eq(new RefundDue(total, RefundReason.RESERVATION_CANCELLED)));
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private ReservationView book(PaymentMode mode) {
        clock.set(BOOKING_INSTANT);
        Slot slot = nextSlot();
        return createReservation.create(new CreateReservationCommand(PROPERTY_ID, "Ada Lovelace", slot.room(),
                slot.start(), slot.start().plusDays(3), SEGMENT_BY_ROOM.get(slot.room()), mode, null));
    }

    /**
     * A {@code CONFIRMED} credit-card reservation without a remote call to credit-card-payment-service: built
     * directly with {@link Reservation#create} and {@link CreditCardPaymentModeHandler} — exactly what
     * {@code CreateReservationUseCase} does internally once its (irrelevant here) payment verification step has
     * already passed — then stored with its outbox row in one transaction. Chosen over
     * {@code @MockitoBean CreditCardPaymentClient}: this test only needs one CONFIRMED-by-construction row as
     * "noise" for {@link #cancelsOnlyBankTransferReservationsPastDeadline()}, and building it directly avoids
     * depending on the generated client's response shape or the resilience4j wiring for a fixture this test isn't
     * otherwise exercising.
     */
    private Reservation creditCardReservation() {
        clock.set(BOOKING_INSTANT);
        Slot slot = nextSlot();
        RoomSegment segment = SEGMENT_BY_ROOM.get(slot.room());
        Property property = catalog.findProperty(PROPERTY_ID).orElseThrow();
        Room room = catalog.findRoom(PROPERTY_ID, slot.room()).orElseThrow();
        Money nightlyRate = catalog.findNightlyRate(PROPERTY_ID, segment).orElseThrow();
        NewReservation request = new NewReservation(new ReservationIdGenerator().next(), "Ada Lovelace", slot.start(),
                slot.start().plusDays(3), segment, PaymentMode.CREDIT_CARD, "cc-ref-" + slot.room() + "-" + slot.start());
        return transactions.execute(status -> {
            Reservation reservation = Reservation.create(
                    request, property, room, nightlyRate, new CreditCardPaymentModeHandler(), clock);
            reservations.add(reservation);
            reservation.pullEvents().forEach(outbox::append);
            return reservation;
        });
    }

    /** The next (room, week) pair from the shared sequence; see the class javadoc. */
    private static Slot nextSlot() {
        int n = SEQ.getAndIncrement();
        return new Slot(ROOMS[n % ROOMS.length], FIRST_STAY.plusWeeks(n));
    }

    private record Slot(String room, LocalDate start) {
    }

    private Map<String, Object> reservationRow(String reservationId) {
        return jdbc.sql("SELECT status, cancellation_reason FROM reservation WHERE reservation_id = :id")
                .param("id", reservationId).query().singleRow();
    }

    private UUID rowIdFor(String reservationId) {
        return jdbc.sql("SELECT id FROM reservation WHERE reservation_id = :id")
                .param("id", reservationId).query(UUID.class).single();
    }

    private long cancelledEventCount(String reservationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox_event
                         WHERE aggregate_id = :id AND event_type = 'ReservationStatusChanged'
                           AND payload->>'status' = 'CANCELLED'
                        """)
                .param("id", reservationId).query(Long.class).single();
    }

    private double cancelledCounterValue() {
        Counter counter = meterRegistry.find("reservation.autocancel.cancelled").tag("propertyId", PROPERTY_ID).counter();
        return counter == null ? 0 : counter.count();
    }

    private double overdueGaugeValue() {
        return meterRegistry.get("reservation.autocancel.overdue").gauge().value();
    }

    private static ReceivedPayment paymentWithId(String paymentId) {
        return argThat(payment -> payment != null && payment.paymentId().equals(paymentId));
    }

    /**
     * Adds a second {@link java.time.Clock} bean every test in this class moves by hand instead of sleeping on.
     * Named differently from the production {@code ClockConfiguration}'s {@code clock()} bean (registering a second
     * bean under the same name is a {@code BeanDefinitionOverrideException}, since this service does not enable
     * bean-definition overriding); {@code @Primary} is what actually makes every {@code Clock}-typed collaborator
     * (the use cases, the job) receive this one instead of the real one.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BOOKING_INSTANT);
        }
    }
}
