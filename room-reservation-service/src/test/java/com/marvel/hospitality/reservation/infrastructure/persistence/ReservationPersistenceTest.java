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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
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
@Import({TestcontainersConfiguration.class, JpaReservationRepositoryAdapter.class, JpaPropertyCatalogAdapter.class})
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
            assertThat(waitUntil(() -> jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                    .query(Integer.class).single() > 0)).as("B blocked on A's uncommitted row").isTrue();
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

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
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
