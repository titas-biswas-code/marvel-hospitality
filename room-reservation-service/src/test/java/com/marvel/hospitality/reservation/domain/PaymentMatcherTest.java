package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;

class PaymentMatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Money TOTAL_AMOUNT = Money.eur("240.00");

    private final PaymentMatcher matcher = new PaymentMatcher();

    private static Reservation reservationInStatus(ReservationStatus status, Money amountReceived) {
        ReservationState state = new ReservationState(
                UUID.randomUUID(),
                ReservationId.of("P4145478"),
                "AMS01",
                "201",
                "Ada Lovelace",
                new StayPeriod(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12")),
                RoomSegment.MEDIUM,
                PaymentMode.BANK_TRANSFER,
                null,
                status,
                status == ReservationStatus.CANCELLED ? CancellationReason.PAYMENT_DEADLINE_MISSED : null,
                TOTAL_AMOUNT,
                amountReceived,
                status == ReservationStatus.PENDING_PAYMENT ? Instant.parse("2026-10-07T22:00:00Z") : null,
                0L,
                NOW,
                NOW);
        return Reservation.rehydrate(state);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @CsvSource({
        "1401541457 P4145478, 1401541457, P4145478",
        "'  1401541457 P4145478  ', 1401541457, P4145478",
        "abcdefghi- P4145478, abcdefghi-, P4145478"
    })
    void matchesValidDescription(String description, String expectedE2eId, String expectedReservationId) {
        Optional<Remittance> remittance = matcher.parse(description);

        assertThat(remittance).contains(new Remittance(expectedE2eId, ReservationId.of(expectedReservationId)));
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @ValueSource(strings = {
        "140154145 P4145478", // 9-char e2e id
        "14015414577 P4145478", // 11-char e2e id
        "1401541457 P414547", // 7-char reservation id
        "1401541457 P41454781", // 9-char reservation id
        "1401541457P4145478", // missing space
        "1401541457  P4145478", // double space
        ""
    })
    void rejectsDescriptionWithWrongLengths(String description) {
        assertThat(matcher.parse(description)).isEmpty();
    }

    @Test
    void rejectsNullDescription() {
        assertThat(matcher.parse(null)).isEmpty();
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @ValueSource(strings = {
        "1401541457 p4145478", // lowercase p
        "1401541457 P414547i", // I
        "1401541457 P414547l", // l
        "1401541457 P414547o", // o
        "1401541457 P414547u", // u
        "1401541457 P414547I", // I
        "1401541457 P414547L", // L
        "1401541457 P414547O", // O
        "1401541457 P414547U" // U
    })
    void rejectsLowercaseReservationId(String description) {
        assertThat(matcher.parse(description)).isEmpty();
    }

    private record ClassificationScenario(
            Money previouslyReceived, Money amount, PaymentMatchOutcome expectedOutcome,
            Money expectedAmountReceived, @Nullable Money expectedRefundAmount) {
    }

    static Stream<ClassificationScenario> classificationScenarios() {
        return Stream.of(
                new ClassificationScenario(Money.zeroEur(), Money.eur("100.00"), PaymentMatchOutcome.MATCHED_PARTIAL, Money.eur("100.00"), null),
                new ClassificationScenario(Money.zeroEur(), Money.eur("240.00"), PaymentMatchOutcome.MATCHED_FULL, Money.eur("240.00"), null),
                new ClassificationScenario(Money.zeroEur(), Money.eur("250.00"), PaymentMatchOutcome.OVERPAID, Money.eur("250.00"), Money.eur("10.00")),
                new ClassificationScenario(Money.eur("120.00"), Money.eur("120.00"), PaymentMatchOutcome.MATCHED_FULL, Money.eur("240.00"), null));
    }

    @ParameterizedTest
    @MethodSource("classificationScenarios")
    void classifiesPartialFullAndOverpaid(ClassificationScenario scenario) {
        Reservation reservation = reservationInStatus(ReservationStatus.PENDING_PAYMENT, scenario.previouslyReceived());

        PaymentMatch result = matcher.classify(reservation, scenario.previouslyReceived(), scenario.amount());

        assertThat(result.outcome()).isEqualTo(scenario.expectedOutcome());
        assertThat(result.amountReceived()).isEqualTo(scenario.expectedAmountReceived());
        assertThat(result.matched()).isTrue();
        if (scenario.expectedRefundAmount() == null) {
            assertThat(result.refund()).isNull();
        } else {
            assertThat(result.refund()).isEqualTo(new RefundDue(scenario.expectedRefundAmount(), RefundReason.OVERPAYMENT));
        }
    }

    @Test
    void classifiesNotPendingForCancelledAndConfirmed() {
        Reservation cancelled = reservationInStatus(ReservationStatus.CANCELLED, Money.zeroEur());
        Reservation confirmed = reservationInStatus(ReservationStatus.CONFIRMED, TOTAL_AMOUNT);

        PaymentMatch cancelledResult = matcher.classify(cancelled, Money.zeroEur(), Money.eur("50.00"));
        PaymentMatch confirmedResult = matcher.classify(confirmed, Money.zeroEur(), Money.eur("50.00"));

        assertThat(cancelledResult.outcome()).isEqualTo(PaymentMatchOutcome.UNMATCHED_NOT_PENDING);
        assertThat(cancelledResult.matched()).isFalse();
        assertThat(cancelledResult.amountReceived()).isNull();
        assertThat(cancelledResult.refund()).isEqualTo(new RefundDue(Money.eur("50.00"), RefundReason.RESERVATION_CANCELLED));

        assertThat(confirmedResult.outcome()).isEqualTo(PaymentMatchOutcome.UNMATCHED_NOT_PENDING);
        assertThat(confirmedResult.matched()).isFalse();
        assertThat(confirmedResult.amountReceived()).isNull();
        assertThat(confirmedResult.refund()).isEqualTo(new RefundDue(Money.eur("50.00"), RefundReason.OVERPAYMENT));
    }

    @Test
    void classifiesUnknownReservation() {
        PaymentMatch result = matcher.classify(null, Money.zeroEur(), Money.eur("50.00"));

        assertThat(result.outcome()).isEqualTo(PaymentMatchOutcome.UNMATCHED_UNKNOWN_RESERVATION);
        assertThat(result.matched()).isFalse();
        assertThat(result.amountReceived()).isNull();
        assertThat(result.refund()).isNull();
    }

    @Test
    void unmatchedFormatHasNoAmountOrRefund() {
        PaymentMatch result = matcher.unmatchedFormat();

        assertThat(result.outcome()).isEqualTo(PaymentMatchOutcome.UNMATCHED_FORMAT);
        assertThat(result.matched()).isFalse();
        assertThat(result.amountReceived()).isNull();
        assertThat(result.refund()).isNull();
    }

    @Test
    void rejectsNonPositiveAmount() {
        Reservation reservation = reservationInStatus(ReservationStatus.PENDING_PAYMENT, Money.zeroEur());

        assertThatThrownBy(() -> matcher.classify(reservation, Money.zeroEur(), Money.zeroEur()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
