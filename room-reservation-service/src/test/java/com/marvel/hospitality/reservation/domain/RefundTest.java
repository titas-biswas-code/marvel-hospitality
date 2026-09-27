package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundTest {

    private static final UUID REFUND_ID = UUID.fromString("d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59");
    private static final Instant REQUESTED_AT = Instant.parse("2026-10-01T09:16:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-01T09:16:05Z");
    private static final ReceivedPayment OVERPAID = new ReceivedPayment("5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
            ReservationId.of("P4145478"), "AMS01", "NL91ABNA0417164300", Money.eur("270.00"),
            "1401541457 P4145478", "1401541457", PaymentMatchOutcome.OVERPAID, REQUESTED_AT);

    @Test
    void requestingRecordsRefundRequestedForThePaymentsReservation() {
        Refund refund = Refund.request(REFUND_ID, OVERPAID, new RefundDue(Money.eur("30.00"), RefundReason.OVERPAYMENT),
                REQUESTED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.completedAt()).isNull();
        assertThat(refund.failureReason()).isNull();
        assertThat(refund.pullEvents()).containsExactly(new RefundRequested(REFUND_ID, OVERPAID.paymentId(),
                ReservationId.of("P4145478"), "AMS01", Money.eur("30.00"), RefundReason.OVERPAYMENT, REQUESTED_AT));
        assertThat(refund.pullEvents()).isEmpty();
    }

    @Test
    void unmatchedPaymentIsNeverRefunded() {
        ReceivedPayment unmatched = new ReceivedPayment("5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f", null, null,
                "NL91ABNA0417164300", Money.eur("50.00"), "thank you", null, PaymentMatchOutcome.UNMATCHED_FORMAT,
                REQUESTED_AT);

        assertThatThrownBy(() -> Refund.request(REFUND_ID, unmatched,
                new RefundDue(Money.eur("50.00"), RefundReason.OVERPAYMENT), REQUESTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not linked to a reservation");
    }

    @Test
    void completingSetsCompletedAt() {
        Refund refund = requested();

        refund.complete(COMPLETED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(refund.failureReason()).isNull();
        assertThat(refund.pullEvents()).isEmpty();
    }

    @Test
    void failingKeepsTheReason() {
        Refund refund = requested();

        refund.fail("CREDITOR_ACCOUNT_REJECTED", COMPLETED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.failureReason()).isEqualTo("CREDITOR_ACCOUNT_REJECTED");
        assertThat(refund.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void anOutcomeIsFinal() {
        Refund completed = requested();
        completed.complete(COMPLETED_AT);
        Refund failed = requested();
        failed.fail("CREDITOR_ACCOUNT_REJECTED", COMPLETED_AT);

        assertThatThrownBy(() -> completed.fail("CREDITOR_ACCOUNT_REJECTED", COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already COMPLETED");
        assertThatThrownBy(() -> failed.complete(COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already FAILED");
        assertThat(completed.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(failed.failureReason()).isEqualTo("CREDITOR_ACCOUNT_REJECTED");
    }

    @Test
    void rehydrationRejectsInconsistentOutcomeColumns() {
        ReservationId reservationId = ReservationId.of("P4145478");

        assertThatThrownBy(() -> Refund.rehydrate(REFUND_ID, OVERPAID.paymentId(), reservationId, "AMS01",
                Money.eur("30.00"), RefundReason.OVERPAYMENT, RefundStatus.FAILED, null, REQUESTED_AT, COMPLETED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Refund.rehydrate(REFUND_ID, OVERPAID.paymentId(), reservationId, "AMS01",
                Money.eur("30.00"), RefundReason.OVERPAYMENT, RefundStatus.COMPLETED, null, REQUESTED_AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Refund.rehydrate(REFUND_ID, OVERPAID.paymentId(), reservationId, "AMS01",
                Money.zeroEur(), RefundReason.OVERPAYMENT, RefundStatus.REQUESTED, null, REQUESTED_AT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rehydrationRecordsNoEvents() {
        Refund refund = Refund.rehydrate(REFUND_ID, OVERPAID.paymentId(), ReservationId.of("P4145478"), "AMS01",
                Money.eur("30.00"), RefundReason.OVERPAYMENT, RefundStatus.REQUESTED, null, REQUESTED_AT, null);

        assertThat(refund.pullEvents()).isEmpty();
    }

    private static Refund requested() {
        Refund refund = Refund.request(UUID.randomUUID(), OVERPAID,
                new RefundDue(Money.eur("30.00"), RefundReason.OVERPAYMENT), REQUESTED_AT);
        refund.pullEvents();
        return refund;
    }
}
