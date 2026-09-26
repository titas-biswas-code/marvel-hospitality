package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReservationTest {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final Property PROPERTY = new Property("AMS01", "Marvel Amsterdam", AMSTERDAM, "NL00MARV0000000001");
    private static final Room MEDIUM_ROOM = new Room("AMS01", "201", RoomSegment.MEDIUM);
    private static final Money NIGHTLY_RATE = Money.eur("120.00");

    private static NewReservation newReservation(PaymentMode mode, LocalDate start, LocalDate end) {
        return new NewReservation(ReservationId.of("P4145478"), "Ada Lovelace", start, end, RoomSegment.MEDIUM, mode, null);
    }

    private static PaymentModeHandler handlerFor(PaymentMode mode) {
        return switch (mode) {
            case CASH -> new CashPaymentModeHandler();
            case BANK_TRANSFER -> new BankTransferPaymentModeHandler(new PaymentDeadlinePolicy());
            case CREDIT_CARD -> new CreditCardPaymentModeHandler();
        };
    }

    private static Reservation createCash() {
        NewReservation request = newReservation(PaymentMode.CASH, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"));
        return Reservation.create(request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), CLOCK);
    }

    private static Reservation createPending() {
        NewReservation request = newReservation(PaymentMode.BANK_TRANSFER, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"));
        return Reservation.create(request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.BANK_TRANSFER), CLOCK);
    }

    @Test
    void cashReservationIsConfirmedOnCreation() {
        Reservation reservation = createCash();

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.paymentDeadlineAt()).isNull();
        assertThat(reservation.totalAmount()).isEqualTo(Money.eur("240.00"));
    }

    @Test
    void confirmsCreditCardReservationImmediately() {
        NewReservation request = new NewReservation(ReservationId.of("P4145478"), "Ada Lovelace",
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), RoomSegment.MEDIUM, PaymentMode.CREDIT_CARD,
                "OK-123");

        Reservation reservation = Reservation.create(
                request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CREDIT_CARD), CLOCK);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.paymentReference()).isEqualTo("OK-123");
        assertThat(reservation.paymentDeadlineAt()).isNull();
        assertThat(reservation.pullEvents()).singleElement()
                .satisfies(event -> assertThat(event.status()).isEqualTo(ReservationStatus.CONFIRMED));
    }

    @Test
    void checkCreatableAppliesTheModeIndependentRulesWithoutCreatingAnything() {
        assertThat(Reservation.checkCreatable(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"),
                RoomSegment.MEDIUM, PROPERTY, MEDIUM_ROOM, CLOCK))
                .isEqualTo(new StayPeriod(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12")));
        assertThatThrownBy(() -> Reservation.checkCreatable(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"),
                RoomSegment.LARGE, PROPERTY, MEDIUM_ROOM, CLOCK))
                .isInstanceOf(RoomSegmentMismatchException.class);
        assertThatThrownBy(() -> Reservation.checkCreatable(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-03"),
                RoomSegment.MEDIUM, PROPERTY, MEDIUM_ROOM, CLOCK))
                .isInstanceOf(InvalidStayException.class);
    }

    static Stream<Arguments> dstDeadlines() {
        return Stream.of(
                Arguments.of(LocalDate.parse("2026-10-10"), Instant.parse("2026-10-07T22:00:00Z")),
                Arguments.of(LocalDate.parse("2026-10-27"), Instant.parse("2026-10-24T22:00:00Z")),
                Arguments.of(LocalDate.parse("2026-10-28"), Instant.parse("2026-10-25T23:00:00Z")));
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("dstDeadlines")
    void bankTransferReservationIsPendingWithDeadlineTwoDaysBeforeStartAtPropertyMidnight(LocalDate start, Instant expectedDeadline) {
        NewReservation request = newReservation(PaymentMode.BANK_TRANSFER, start, start.plusDays(2));

        Reservation reservation = Reservation.create(request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.BANK_TRANSFER), CLOCK);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(reservation.paymentDeadlineAt()).isEqualTo(expectedDeadline);
        assertThat(reservation.totalAmount()).isEqualTo(NIGHTLY_RATE.times(2));
    }

    @Test
    void rejectsStayLongerThanThirtyNights() {
        LocalDate start = LocalDate.parse("2026-10-10");
        NewReservation request = newReservation(PaymentMode.CASH, start, start.plusDays(31));

        assertThatThrownBy(() -> Reservation.create(request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), CLOCK))
                .isInstanceOf(InvalidStayException.class)
                .satisfies(e -> assertThat(((InvalidStayException) e).field()).isEqualTo("endDate"));
    }

    @Test
    void acceptsExactlyThirtyNights() {
        LocalDate start = LocalDate.parse("2026-10-10");
        NewReservation request = newReservation(PaymentMode.CASH, start, start.plusDays(30));

        Reservation reservation = Reservation.create(request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), CLOCK);

        assertThat(reservation.stay().nights()).isEqualTo(30);
    }

    @Test
    void rejectsEndDateNotAfterStartDate() {
        LocalDate start = LocalDate.parse("2026-10-10");
        NewReservation sameDate = newReservation(PaymentMode.CASH, start, start);
        NewReservation earlierEnd = newReservation(PaymentMode.CASH, start, start.minusDays(1));

        assertThatThrownBy(() -> Reservation.create(sameDate, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), CLOCK))
                .isInstanceOf(InvalidStayException.class);
        assertThatThrownBy(() -> Reservation.create(earlierEnd, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), CLOCK))
                .isInstanceOf(InvalidStayException.class);
    }

    @Test
    void rejectsStartDateInThePastForPropertyTimezone() {
        // 2026-09-26T22:30:00Z is already 2026-09-27 in Amsterdam (CEST, UTC+2).
        Clock clockNearMidnight = Clock.fixed(Instant.parse("2026-09-26T22:30:00Z"), ZoneOffset.UTC);

        NewReservation yesterday = newReservation(PaymentMode.CASH, LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-28"));
        assertThatThrownBy(() -> Reservation.create(yesterday, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), clockNearMidnight))
                .isInstanceOf(InvalidStayException.class)
                .satisfies(e -> assertThat(((InvalidStayException) e).field()).isEqualTo("startDate"));

        NewReservation today = newReservation(PaymentMode.CASH, LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-29"));
        Reservation reservation = Reservation.create(today, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), clockNearMidnight);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void rejectsSegmentThatDoesNotMatchTheRoom() {
        NewReservation request = new NewReservation(
                ReservationId.of("P4145478"),
                "Ada Lovelace",
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-12"),
                RoomSegment.LARGE,
                PaymentMode.CASH,
                null);

        assertThatThrownBy(() -> Reservation.create(request, PROPERTY, MEDIUM_ROOM, NIGHTLY_RATE, handlerFor(PaymentMode.CASH), CLOCK))
                .isInstanceOf(RoomSegmentMismatchException.class);
    }

    @Test
    void pendingReservationCanBeConfirmed() {
        Reservation reservation = createPending();

        reservation.confirm(CLOCK);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void pendingReservationCanBeCancelled() {
        Reservation reservation = createPending();

        reservation.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, CLOCK);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.cancellationReason()).isEqualTo(CancellationReason.PAYMENT_DEADLINE_MISSED);
    }

    @Test
    void confirmedReservationCannotBeCancelledByDeadline() {
        Reservation reservation = createCash();

        assertThatThrownBy(() -> reservation.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, CLOCK))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void cancelledReservationCannotBeConfirmed() {
        Reservation reservation = createPending();
        reservation.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, CLOCK);

        assertThatThrownBy(() -> reservation.confirm(CLOCK)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void failedTransitionLeavesStateAndEventsUntouched() {
        Reservation reservation = createCash();
        reservation.pullEvents();
        Instant updatedAtBefore = reservation.updatedAt();

        assertThatThrownBy(() -> reservation.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, CLOCK))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.cancellationReason()).isNull();
        assertThat(reservation.updatedAt()).isEqualTo(updatedAtBefore);
        assertThat(reservation.pullEvents()).isEmpty();
    }

    @Test
    void recordPaymentAccumulatesAmountReceivedWithoutChangingStatus() {
        Reservation reservation = createPending();
        reservation.pullEvents();

        reservation.recordPayment(Money.eur("100.00"), CLOCK);
        reservation.recordPayment(Money.eur("40.00"), CLOCK);

        assertThat(reservation.amountReceived()).isEqualTo(Money.eur("140.00"));
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(reservation.pullEvents()).isEmpty();
    }

    @Test
    void bankTransferInstructionsMatchTheContractFormat() {
        Reservation reservation = createPending();

        assertThat(reservation.bankTransferInstructions("NL00MARV0000000001"))
                .isEqualTo("Transfer 240.00 EUR to NL00MARV0000000001 with description '<your E2E id> P4145478'");
    }

    @Test
    void cashReservationHasNoBankTransferInstructions() {
        Reservation reservation = createCash();

        assertThat(reservation.bankTransferInstructions("NL00MARV0000000001")).isNull();
    }

    @Test
    void rehydratedReservationRoundTripsItsState() {
        Reservation original = createPending();
        original.recordPayment(Money.eur("50.00"), CLOCK);
        ReservationState state = original.snapshot();

        Reservation rehydrated = Reservation.rehydrate(state);

        assertThat(rehydrated.snapshot()).isEqualTo(state);
        assertThat(rehydrated.pullEvents()).isEmpty();
    }

    private record TransitionScenario(
            String name,
            Reservation reservation,
            @Nullable ReservationStatus expectedPrevious,
            ReservationStatus expectedStatus,
            @Nullable StatusChangeReason expectedReason) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<TransitionScenario> transitionScenarios() {
        Reservation confirmed = createPending();
        confirmed.pullEvents();
        confirmed.confirm(CLOCK);

        Reservation cancelled = createPending();
        cancelled.pullEvents();
        cancelled.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, CLOCK);

        return Stream.of(
                new TransitionScenario("create-cash", createCash(), null, ReservationStatus.CONFIRMED, null),
                new TransitionScenario("create-bank-transfer", createPending(), null, ReservationStatus.PENDING_PAYMENT, null),
                new TransitionScenario(
                        "confirm", confirmed, ReservationStatus.PENDING_PAYMENT, ReservationStatus.CONFIRMED, StatusChangeReason.PAYMENT_RECEIVED),
                new TransitionScenario(
                        "cancel", cancelled, ReservationStatus.PENDING_PAYMENT, ReservationStatus.CANCELLED, StatusChangeReason.PAYMENT_DEADLINE_MISSED));
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @MethodSource("transitionScenarios")
    void everyTransitionRecordsAStatusChangedEvent(TransitionScenario scenario) {
        List<ReservationStatusChanged> events = scenario.reservation().pullEvents();

        assertThat(events).hasSize(1);
        ReservationStatusChanged event = events.get(0);
        assertThat(event.reservationId()).isEqualTo(ReservationId.of("P4145478"));
        assertThat(event.propertyId()).isEqualTo("AMS01");
        assertThat(event.customerName()).isEqualTo("Ada Lovelace");
        assertThat(event.roomNumber()).isEqualTo("201");
        assertThat(event.startDate()).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(event.endDate()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(event.paymentMode()).isEqualTo(scenario.reservation().paymentMode());
        assertThat(event.previousStatus()).isEqualTo(scenario.expectedPrevious());
        assertThat(event.status()).isEqualTo(scenario.expectedStatus());
        assertThat(event.reason()).isEqualTo(scenario.expectedReason());
        assertThat(event.totalAmount()).isEqualTo(Money.eur("240.00"));
        assertThat(event.amountReceived()).isEqualTo(Money.zeroEur());
        assertThat(event.paymentDeadlineAt()).isEqualTo(scenario.reservation().paymentDeadlineAt());
        assertThat(event.occurredAt()).isEqualTo(FIXED_NOW);
    }
}
