package com.marvel.hospitality.reservation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.platform.problem.ConstraintNames;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.PropertyCatalog;
import com.marvel.hospitality.reservation.application.PaymentReferenceAlreadyUsedException;
import com.marvel.hospitality.reservation.application.ReservationIdCollisionException;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.application.RoomUnavailableException;
import com.marvel.hospitality.reservation.domain.CancellationReason;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationState;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import com.marvel.hospitality.reservation.domain.StayPeriod;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link JpaReservationRepositoryAdapter} and {@link JpaPropertyCatalogAdapter} against a real Postgres
 * (ADR-0003, ADR-0005): the exclusion constraint and the business-id unique index are the whole point, and
 * neither exists on H2, so this is never mocked out.
 *
 * <p>{@code @DataJpaTest} in Boot 4.1 is meta-annotated with {@code @AutoConfigureTestDatabase(replace = NON_TEST)},
 * which would otherwise swap the {@code @ServiceConnection} Testcontainers datasource for an embedded one; there
 * is deliberately no embedded database on the classpath (ADR-0003: never H2), so {@code replace = NONE} is
 * required here, not optional. Boot 4.1's {@code @DataJpaTest} also no longer imports
 * {@code FlywayAutoConfiguration} by default (unlike Boot 3's slice), so it is added back explicitly — the schema
 * must come only from Flyway, never from Hibernate DDL generation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, JpaReservationRepositoryAdapter.class, JpaPropertyCatalogAdapter.class,
        JpaReceivedPaymentRepositoryAdapter.class})
class ReservationPersistenceTest {

    private static final Instant SOME_INSTANT = Instant.parse("2026-09-26T10:00:00Z");

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private PropertyCatalog catalog;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flywayMigratesCleanlyOnEmptyDatabase() {
        // Every JPA entity mapping was itself validated (spring.jpa.hibernate.ddl-auto=validate) just by this
        // context starting; a mismatch between an entity and the Flyway-created schema would have failed here.
        Boolean btreeGistPresent = jdbc.sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist')")
                .query(Boolean.class)
                .single();
        assertThat(btreeGistPresent).isTrue();

        String exclusionConstraintType = jdbc.sql("""
                        SELECT contype FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        WHERE t.relname = 'reservation' AND c.conname = 'reservation_no_overlap'
                        """)
                .query(String.class)
                .single();
        assertThat(exclusionConstraintType).isEqualTo("x");

        Long propertyCount = jdbc.sql("SELECT count(*) FROM property").query(Long.class).single();
        Long roomCount = jdbc.sql("SELECT count(*) FROM room").query(Long.class).single();
        Long rateCount = jdbc.sql("SELECT count(*) FROM room_rate").query(Long.class).single();
        assertThat(propertyCount).isEqualTo(2L);
        assertThat(roomCount).isEqualTo(12L);
        assertThat(rateCount).isEqualTo(8L);
    }

    @Test
    void overlappingStayOnSameRoomIsRejectedByExclusionConstraint() {
        reservations.add(reservation("P0000001", "AMS01", "101", LocalDate.parse("2030-01-10"), LocalDate.parse("2030-01-15")));

        assertThatThrownBy(() -> reservations.add(
                reservation("P0000002", "AMS01", "101", LocalDate.parse("2030-01-12"), LocalDate.parse("2030-01-20"))))
                .isInstanceOf(RoomUnavailableException.class);
    }

    @Test
    void backToBackStaysOnSameRoomAreAllowed() {
        reservations.add(reservation("P0000003", "AMS01", "102", LocalDate.parse("2030-02-01"), LocalDate.parse("2030-02-05")));

        // Checkout on the 5th, next check-in also on the 5th: the half-open range must not treat this as overlap.
        reservations.add(reservation("P0000004", "AMS01", "102", LocalDate.parse("2030-02-05"), LocalDate.parse("2030-02-10")));
    }

    @Test
    void cancelledReservationDoesNotBlockTheRoom() {
        reservations.add(reservation(
                "P0000005", "AMS01", "201", LocalDate.parse("2030-03-01"), LocalDate.parse("2030-03-05"), ReservationStatus.CANCELLED));

        // Same room, fully overlapping dates: only blocked if the exclusion constraint still counted the cancelled row.
        reservations.add(reservation(
                "P0000006", "AMS01", "201", LocalDate.parse("2030-03-01"), LocalDate.parse("2030-03-05"), ReservationStatus.PENDING_PAYMENT));
    }

    @Test
    void creditCardPaymentReferenceCanBackOnlyOneReservationAcrossProperties() {
        reservations.add(paidReservation("P0000040", "AMS01", "101", LocalDate.parse("2034-01-10"),
                LocalDate.parse("2034-01-12"), PaymentMode.CREDIT_CARD, "OK-UNIQUE-1"));
        assertThat(reservations.isPaymentReferenceUsed(PaymentMode.CREDIT_CARD, "OK-UNIQUE-1")).isTrue();
        assertThat(reservations.isPaymentReferenceUsed(PaymentMode.CREDIT_CARD, "OK-UNIQUE-2")).isFalse();

        // Last: the violation aborts this test's Postgres transaction, so nothing may query after it.
        // Different property, different room and dates: only the reference is shared, and that alone is rejected.
        assertThatThrownBy(() -> reservations.add(paidReservation("P0000041", "RTM01", "202",
                LocalDate.parse("2034-02-10"), LocalDate.parse("2034-02-12"), PaymentMode.CREDIT_CARD, "OK-UNIQUE-1")))
                .isInstanceOf(PaymentReferenceAlreadyUsedException.class)
                .hasMessageContaining("OK-UNIQUE-1");
    }

    @Test
    void cashAndBankTransferReferencesMayRepeat() {
        reservations.add(paidReservation("P0000042", "AMS01", "101", LocalDate.parse("2034-03-10"),
                LocalDate.parse("2034-03-12"), PaymentMode.CASH, "FRONT-DESK-1"));
        reservations.add(paidReservation("P0000043", "AMS01", "102", LocalDate.parse("2034-03-10"),
                LocalDate.parse("2034-03-12"), PaymentMode.CASH, "FRONT-DESK-1"));
        // The card index is partial: a card payment may even share its reference with a cash one.
        reservations.add(paidReservation("P0000044", "AMS01", "201", LocalDate.parse("2034-03-10"),
                LocalDate.parse("2034-03-12"), PaymentMode.CREDIT_CARD, "FRONT-DESK-1"));

        assertThat(reservations.isPaymentReferenceUsed(PaymentMode.CASH, "FRONT-DESK-1")).isTrue();
    }

    @Test
    void isBookedMirrorsTheExclusionConstraint() {
        reservations.add(reservation("P0000030", "AMS01", "102", LocalDate.parse("2033-01-10"), LocalDate.parse("2033-01-15")));

        // Overlapping non-cancelled stay on the same room: booked.
        assertThat(reservations.isBooked("AMS01", "102",
                new StayPeriod(LocalDate.parse("2033-01-12"), LocalDate.parse("2033-01-20")))).isTrue();

        // Adjacent stay (this stay's start equals the existing reservation's end): the half-open range must not
        // treat checkout-day-equals-next-checkin-day as an overlap, exactly like the exclusion constraint.
        assertThat(reservations.isBooked("AMS01", "102",
                new StayPeriod(LocalDate.parse("2033-01-15"), LocalDate.parse("2033-01-18")))).isFalse();

        // Same dates, different room: not booked.
        assertThat(reservations.isBooked("AMS01", "101",
                new StayPeriod(LocalDate.parse("2033-01-10"), LocalDate.parse("2033-01-15")))).isFalse();

        // A cancelled reservation must not block the room, same as the exclusion constraint's
        // WHERE (status <> 'CANCELLED').
        reservations.add(reservation("P0000031", "AMS01", "301",
                LocalDate.parse("2033-02-01"), LocalDate.parse("2033-02-05"), ReservationStatus.CANCELLED));
        assertThat(reservations.isBooked("AMS01", "301",
                new StayPeriod(LocalDate.parse("2033-02-01"), LocalDate.parse("2033-02-05")))).isFalse();
    }

    @Test
    void sameDatesOnSameRoomNumberInAnotherPropertyAreAllowed() {
        reservations.add(reservation("P0000007", "AMS01", "201", LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-04")));

        reservations.add(reservation("P0000008", "RTM01", "201", LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-04")));
    }

    @Test
    void duplicateBusinessIdIsReportedAsIdCollision() {
        reservations.add(reservation("P0000009", "AMS01", "301", LocalDate.parse("2030-05-01"), LocalDate.parse("2030-05-03")));

        // Different room and dates, so only the unique index on reservation_id can reject this, never the
        // exclusion constraint — isolates which constraint the adapter is actually mapping.
        assertThatThrownBy(() -> reservations.add(
                reservation("P0000009", "AMS01", "401", LocalDate.parse("2031-06-01"), LocalDate.parse("2031-06-03"))))
                .isInstanceOf(ReservationIdCollisionException.class);
    }

    @Test
    void reservationRoundTripsThroughTheDatabase() {
        Instant deadline = Instant.parse("2030-06-28T22:00:00Z");
        ReservationState original = new ReservationState(
                UUID.randomUUID(),
                ReservationId.of("P0000011"),
                "AMS01",
                "401",
                "Ada Lovelace",
                new StayPeriod(LocalDate.parse("2030-07-01"), LocalDate.parse("2030-07-03")),
                RoomSegment.EXTRA_LARGE,
                PaymentMode.BANK_TRANSFER,
                "some reference",
                ReservationStatus.PENDING_PAYMENT,
                null,
                Money.eur("520.00"),
                Money.eur("120.00"),
                deadline,
                0L,
                SOME_INSTANT,
                SOME_INSTANT);
        reservations.add(Reservation.rehydrate(original));

        Optional<Reservation> found = reservations.find("AMS01", ReservationId.of("P0000011"));

        assertThat(found).isPresent();
        assertThat(found.get().snapshot()).isEqualTo(original);
    }

    @Test
    void findDoesNotReturnAReservationOfAnotherProperty() {
        reservations.add(reservation("P0000012", "AMS01", "101", LocalDate.parse("2030-08-01"), LocalDate.parse("2030-08-03")));

        assertThat(reservations.find("RTM01", ReservationId.of("P0000012"))).isEmpty();
        assertThat(reservations.find("AMS01", ReservationId.of("P0000012"))).isPresent();
    }

    @Test
    void findForUpdateFindsByReservationIdAcrossProperties() {
        reservations.add(reservation("P0000954", "RTM01", "101", LocalDate.parse("2036-01-05"), LocalDate.parse("2036-01-08")));

        // findForUpdate takes only the business id (ADR-0009: the bank topic carries no propertyId), unlike
        // find(propertyId, reservationId) which is scoped.
        Optional<Reservation> found = reservations.findForUpdate(ReservationId.of("P0000954"));

        assertThat(found).isPresent();
        assertThat(found.get().propertyId()).isEqualTo("RTM01");
    }

    @Test
    void updateWritesMutableStateAndBumpsVersion() {
        String reservationId = "P0000952";
        reservations.add(reservation(reservationId, "AMS01", "102", LocalDate.parse("2036-01-01"), LocalDate.parse("2036-01-04"),
                ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER, "some-reference"));

        Reservation loaded = reservations.findForUpdate(ReservationId.of(reservationId)).orElseThrow();
        assertThat(loaded.version()).isEqualTo(0L);
        loaded.recordPartialPayment(Money.eur("100.00"), Clock.systemUTC());
        reservations.update(loaded);

        Reservation reloaded = reservations.find("AMS01", ReservationId.of(reservationId)).orElseThrow();
        assertThat(reloaded.amountReceived()).isEqualTo(Money.eur("100.00"));
        assertThat(reloaded.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(reloaded.version()).isEqualTo(1L);
    }

    /**
     * Two independent snapshots of the same row (version 0), both loaded before either is written back: the second
     * {@link ReservationRepository#update} must be rejected rather than silently overwrite the first payment. Each
     * snapshot is loaded and written back in its own {@code PROPAGATION_REQUIRES_NEW} transaction — a genuinely new
     * connection and persistence context each time — because two loads sharing the {@code @DataJpaTest} slice's
     * single test-managed transaction would share one Hibernate first-level cache: the second load would return the
     * very same managed entity the first load (and any update through it) already mutated, so it could never look
     * stale. {@code REQUIRES_NEW} always opens and really commits its own transaction, whether or not the ambient
     * test-managed transaction is present, so no {@code @Transactional(NOT_SUPPORTED)} is needed here (unlike
     * {@link #findForUpdateLocksTheRow}, where the row also has to be visible to a genuinely separate thread).
     */
    @Test
    void updateRejectsAStaleSnapshot() {
        String reservationId = "P0000953";
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            requiresNew.executeWithoutResult(status -> reservations.add(
                    reservation(reservationId, "AMS01", "201", LocalDate.parse("2036-01-11"), LocalDate.parse("2036-01-13"),
                            ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER, "some-reference")));

            Reservation snapshotA = requiresNew.execute(status ->
                    reservations.findForUpdate(ReservationId.of(reservationId)).orElseThrow());
            Reservation snapshotB = requiresNew.execute(status ->
                    reservations.findForUpdate(ReservationId.of(reservationId)).orElseThrow());
            assertThat(snapshotA.version()).isEqualTo(0L);
            assertThat(snapshotB.version()).isEqualTo(0L);

            snapshotA.recordPartialPayment(Money.eur("50.00"), Clock.systemUTC());
            requiresNew.executeWithoutResult(status -> reservations.update(snapshotA));

            snapshotB.recordPartialPayment(Money.eur("60.00"), Clock.systemUTC());
            // Observed, not assumed: this is the raw jakarta.persistence.OptimisticLockException that
            // ReservationEntity.applyChanges throws itself, not a Spring Data wrapper. Even though
            // JpaReservationRepositoryAdapter is a @Repository, Spring's exception translation apparently does not
            // rewrite it here — most likely because nothing in this call path ever reaches Hibernate (the manual
            // version check runs, and throws, before entityManager.flush() executes any SQL), so callers of
            // ReservationRepository#update must be ready to catch the plain JPA exception, not Spring's.
            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> reservations.update(snapshotB)))
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining(reservationId);
        } finally {
            // Real commits (REQUIRES_NEW), unlike the rest of this class's rollback-only test transaction: clean up
            // explicitly so a reused container does not see a duplicate reservation_id on the next run.
            requiresNew.executeWithoutResult(status ->
                    jdbc.sql("DELETE FROM reservation WHERE reservation_id = :id").param("id", reservationId).update());
        }
    }

    /**
     * Proves {@link ReservationRepository#findForUpdate} really takes {@code SELECT ... FOR UPDATE}, not just a
     * plain read: while a holder thread keeps a real, open transaction on the row, a second, completely independent
     * session's {@code SELECT ... FOR UPDATE NOWAIT} on that same row must fail immediately with SQLSTATE
     * {@code 55P03} (lock_not_available) rather than block or succeed.
     *
     * <p>Runs with the test's own (rollback-only) transaction suspended, like
     * {@link #concurrentInsertsForSameRoomOnlyOneSucceeds}: the row must be really committed (not just present in
     * this test method's uncommitted transaction) before the holder thread's independent session can even see it.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findForUpdateLocksTheRow() throws InterruptedException {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        String reservationId = "P0000955";
        transactions.executeWithoutResult(status -> reservations.add(
                reservation(reservationId, "AMS01", "101", LocalDate.parse("2036-01-20"), LocalDate.parse("2036-01-25"))));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            reservations.findForUpdate(ReservationId.of(reservationId));
            locked.countDown();
            await(release);
        }), "find-for-update-holder");

        try {
            holder.start();
            assertThat(locked.await(5, TimeUnit.SECONDS)).as("holder thread acquired the row lock").isTrue();

            assertThatThrownBy(() -> jdbc.sql("SELECT 1 FROM reservation WHERE reservation_id = :id FOR UPDATE NOWAIT")
                    .param("id", reservationId)
                    .query(Integer.class)
                    .single())
                    .matches(ex -> ConstraintNames.sqlState(ex).orElse("").equals("55P03"),
                            "carries SQLSTATE 55P03 (lock_not_available)");
        } finally {
            release.countDown();
            holder.join(5000);
            jdbc.sql("DELETE FROM reservation WHERE reservation_id = :id").param("id", reservationId).update();
        }
    }

    @Test
    void propertyCatalogReadsSeedReferenceData() {
        Optional<Property> property = catalog.findProperty("AMS01");
        assertThat(property).contains(new Property("AMS01", "Marvel Amsterdam", ZoneId.of("Europe/Amsterdam"), "NL00MARV0000000001"));

        Optional<Room> room = catalog.findRoom("AMS01", "301");
        assertThat(room).contains(new Room("AMS01", "301", RoomSegment.LARGE));

        Optional<Money> rate = catalog.findNightlyRate("AMS01", RoomSegment.LARGE);
        assertThat(rate).contains(Money.eur("180.00"));

        assertThat(catalog.findProperty("XXX99")).isEmpty();
        assertThat(catalog.findRoom("AMS01", "999")).isEmpty();
    }

    /**
     * The exclusion constraint (ADR-0005) is enforced through a GiST index probe at insert time: a conflicting
     * uncommitted row makes the second inserter's statement <em>block</em>, not fail immediately. This test makes
     * that real: thread A inserts and holds its transaction open past the point where thread B, in its own
     * transaction, attempts an overlapping insert and blocks inside Postgres; only once A commits does B's
     * blocked statement resolve — and it must resolve as a failure with SQLSTATE {@code 23P01}.
     *
     * <p>Runs with the test's own (rollback-only) transaction suspended, because two independent, really-committing
     * transactions are required for the constraint to ever see an uncommitted conflicting row; the two rows this
     * test commits are deleted again at the end so later tests are unaffected.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentInsertsForSameRoomOnlyOneSucceeds() throws InterruptedException {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        String roomNumber = "102";
        LocalDate start = LocalDate.parse("2032-01-10");
        LocalDate end = LocalDate.parse("2032-01-15");

        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch bAboutToInsert = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<@Nullable Exception> bFailure = new AtomicReference<>();

        Thread threadA = new Thread(() -> transactions.executeWithoutResult(status -> {
            reservations.add(reservation("P00000A0", "RTM01", roomNumber, start, end));
            successes.incrementAndGet();
            aInserted.countDown();
            await(releaseA);
        }), "concurrent-insert-A");

        Thread threadB = new Thread(() -> {
            await(aInserted);
            bAboutToInsert.countDown();
            try {
                transactions.executeWithoutResult(status ->
                        reservations.add(reservation("P00000B0", "RTM01", roomNumber, start, end)));
                successes.incrementAndGet();
            } catch (Exception ex) {
                bFailure.set(ex);
            }
        }, "concurrent-insert-B");

        try {
            threadA.start();
            threadB.start();
            assertThat(bAboutToInsert.await(5, TimeUnit.SECONDS)).isTrue();
            // Let A commit only once B is really blocked inside Postgres, waiting on A's uncommitted row
            // (ADR-0005): an ungranted lock is the database-visible signal of that wait.
            Awaitility.await("B blocked on A's uncommitted row").atMost(Duration.ofSeconds(5))
                    .until(() -> jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                            .query(Integer.class).single() > 0);
            releaseA.countDown();
            threadA.join(5000);
            threadB.join(5000);

            assertThat(successes.get()).isEqualTo(1);
            assertThat(bFailure.get()).isInstanceOf(RoomUnavailableException.class);
            assertThat(ConstraintNames.sqlState(bFailure.get())).contains("23P01");
        } finally {
            releaseA.countDown();
            jdbc.sql("DELETE FROM reservation WHERE reservation_id IN ('P00000A0', 'P00000B0')").update();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Reservation reservation(String reservationId, String propertyId, String roomNumber, LocalDate start, LocalDate end) {
        return reservation(reservationId, propertyId, roomNumber, start, end, ReservationStatus.PENDING_PAYMENT);
    }

    private static Reservation reservation(
            String reservationId, String propertyId, String roomNumber, LocalDate start, LocalDate end, ReservationStatus status) {
        return reservation(reservationId, propertyId, roomNumber, start, end, status, PaymentMode.CASH, null);
    }

    private static Reservation paidReservation(String reservationId, String propertyId, String roomNumber, LocalDate start,
            LocalDate end, PaymentMode mode, String paymentReference) {
        return reservation(reservationId, propertyId, roomNumber, start, end, ReservationStatus.CONFIRMED, mode,
                paymentReference);
    }

    private static Reservation reservation(String reservationId, String propertyId, String roomNumber, LocalDate start,
            LocalDate end, ReservationStatus status, PaymentMode mode, @Nullable String paymentReference) {
        return Reservation.rehydrate(new ReservationState(
                UUID.randomUUID(),
                ReservationId.of(reservationId),
                propertyId,
                roomNumber,
                "Ada Lovelace",
                new StayPeriod(start, end),
                segmentOfSeededRoom(roomNumber),
                mode,
                paymentReference,
                status,
                status == ReservationStatus.CANCELLED ? CancellationReason.PAYMENT_DEADLINE_MISSED : null,
                Money.eur("240.00"),
                Money.zeroEur(),
                null,
                0L,
                SOME_INSTANT,
                SOME_INSTANT));
    }

    /** Matches the seeded {@code room} rows (database-schemas.md) so {@code reservation.room_segment} is realistic. */
    private static RoomSegment segmentOfSeededRoom(String roomNumber) {
        return switch (roomNumber) {
            case "101", "102" -> RoomSegment.SMALL;
            case "201", "202" -> RoomSegment.MEDIUM;
            case "301" -> RoomSegment.LARGE;
            case "401" -> RoomSegment.EXTRA_LARGE;
            default -> throw new IllegalArgumentException("Not a seeded room: " + roomNumber);
        };
    }
}
