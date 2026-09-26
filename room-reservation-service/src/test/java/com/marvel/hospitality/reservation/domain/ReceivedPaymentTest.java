package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceivedPaymentTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-26T10:00:00Z");

    @Test
    void matchedPaymentCarriesReservationPropertyAndE2eId() {
        ReceivedPayment payment = new ReceivedPayment(
                UUID.randomUUID().toString(),
                ReservationId.of("P4145478"),
                "AMS01",
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "1401541457 P4145478",
                "1401541457",
                PaymentMatchOutcome.MATCHED_PARTIAL,
                RECEIVED_AT);

        assertThat(payment.reservationId()).isEqualTo(ReservationId.of("P4145478"));
        assertThat(payment.propertyId()).isEqualTo("AMS01");
        assertThat(payment.e2eId()).isEqualTo("1401541457");
    }

    @Test
    void unmatchedFormatHasNoReservationPropertyOrE2eId() {
        ReceivedPayment payment = new ReceivedPayment(
                UUID.randomUUID().toString(),
                null,
                null,
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "garbage",
                null,
                PaymentMatchOutcome.UNMATCHED_FORMAT,
                RECEIVED_AT);

        assertThat(payment.reservationId()).isNull();
        assertThat(payment.propertyId()).isNull();
        assertThat(payment.e2eId()).isNull();
    }

    @Test
    void unmatchedUnknownReservationHasNoReservationOrPropertyButKeepsE2eId() {
        ReceivedPayment payment = new ReceivedPayment(
                UUID.randomUUID().toString(),
                null,
                null,
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "1401541457 P9999999",
                "1401541457",
                PaymentMatchOutcome.UNMATCHED_UNKNOWN_RESERVATION,
                RECEIVED_AT);

        assertThat(payment.reservationId()).isNull();
        assertThat(payment.propertyId()).isNull();
        assertThat(payment.e2eId()).isEqualTo("1401541457");
    }

    @Test
    void rejectsReservationIdWithoutPropertyIdOrViceVersa() {
        assertThatThrownBy(() -> new ReceivedPayment(
                UUID.randomUUID().toString(),
                ReservationId.of("P4145478"),
                null,
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "1401541457 P4145478",
                "1401541457",
                PaymentMatchOutcome.MATCHED_FULL,
                RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReceivedPayment(
                UUID.randomUUID().toString(),
                null,
                "AMS01",
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "1401541457 P4145478",
                "1401541457",
                PaymentMatchOutcome.MATCHED_FULL,
                RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMatchedOutcomeWithoutReservationAndPropertyIds() {
        assertThatThrownBy(() -> new ReceivedPayment(
                UUID.randomUUID().toString(),
                null,
                null,
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "1401541457 P4145478",
                "1401541457",
                PaymentMatchOutcome.MATCHED_FULL,
                RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingE2eIdUnlessUnmatchedFormat() {
        assertThatThrownBy(() -> new ReceivedPayment(
                UUID.randomUUID().toString(),
                ReservationId.of("P4145478"),
                "AMS01",
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "1401541457 P4145478",
                null,
                PaymentMatchOutcome.MATCHED_FULL,
                RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPresentE2eIdForUnmatchedFormat() {
        assertThatThrownBy(() -> new ReceivedPayment(
                UUID.randomUUID().toString(),
                null,
                null,
                "NL91ABNA0417164300",
                Money.eur("100.00"),
                "garbage",
                "1401541457",
                PaymentMatchOutcome.UNMATCHED_FORMAT,
                RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
