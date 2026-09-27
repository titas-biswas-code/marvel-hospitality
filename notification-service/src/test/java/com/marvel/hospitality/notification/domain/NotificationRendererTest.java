package com.marvel.hospitality.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * One test per template plus the {@code UNKNOWN} fallbacks. Events are built the way the reservation service emits
 * them (events.md: which {@code (previousStatus, status, reason)} triples exist).
 */
class NotificationRendererTest {

    private static final Instant DEADLINE = Instant.parse("2027-10-07T22:00:00Z");

    @Test
    void rendersCreatedPendingPaymentWithBankInstructions() {
        RenderedNotification rendered = NotificationRenderer.render(
                notice("BANK_TRANSFER", null, "PENDING_PAYMENT", null, "0.00", DEADLINE));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.RESERVATION_CREATED_PENDING_PAYMENT);
        assertThat(rendered.text())
                .startsWith("Dear Ada Lovelace,")
                .contains("reservation P4145478: room 201 at property AMS01, from 2027-10-10 to 2027-10-12")
                .contains("Please transfer EUR 240.00")
                .contains("with the description \"<your E2E id> P4145478\"")
                .contains("so that it arrives by 2027-10-07 22:00 UTC")
                .contains("cancelled automatically");
    }

    @Test
    void rendersConfirmedTemplate() {
        RenderedNotification rendered = NotificationRenderer.render(
                notice("BANK_TRANSFER", "PENDING_PAYMENT", "CONFIRMED", "PAYMENT_RECEIVED", "240.00", DEADLINE));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.RESERVATION_CONFIRMED);
        assertThat(rendered.text())
                .contains("your reservation P4145478 is confirmed: room 201 at property AMS01, from 2027-10-10 to 2027-10-12")
                .contains("Total: EUR 240.00 (payment: BANK_TRANSFER, received so far: EUR 240.00)");
    }

    @Test
    void rendersConfirmedTemplateForReservationConfirmedAtBooking() {
        // CASH and CREDIT_CARD reservations are created CONFIRMED: previousStatus and reason are null.
        RenderedNotification rendered = NotificationRenderer.render(
                notice("CASH", null, "CONFIRMED", null, "0.00", null));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.RESERVATION_CONFIRMED);
        assertThat(rendered.text()).contains("is confirmed").contains("payment: CASH, received so far: EUR 0.00");
    }

    @Test
    void rendersPartialPaymentWithRemainingAmount() {
        RenderedNotification rendered = NotificationRenderer.render(notice(
                "BANK_TRANSFER", "PENDING_PAYMENT", "PENDING_PAYMENT", "PARTIAL_PAYMENT_RECEIVED", "90.50", DEADLINE));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.PARTIAL_PAYMENT_RECEIVED);
        assertThat(rendered.text())
                .contains("Received so far: EUR 90.50 of EUR 240.00.")
                .contains("Remaining: EUR 149.50, due by 2027-10-07 22:00 UTC.");
    }

    @Test
    void rendersCancelledTemplate() {
        RenderedNotification rendered = NotificationRenderer.render(notice(
                "BANK_TRANSFER", "PENDING_PAYMENT", "CANCELLED", "PAYMENT_DEADLINE_MISSED", "120.00", DEADLINE));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED);
        assertThat(rendered.text())
                .contains("your reservation P4145478 (room 201 at property AMS01, from 2027-10-10 to 2027-10-12) "
                        + "has been cancelled")
                .contains("the full amount of EUR 240.00 did not arrive by the payment deadline (2027-10-07 22:00 UTC)")
                .contains("Received before the deadline: EUR 120.00.");
    }

    @Test
    void unknownCombinationRendersAsUnknown() {
        // Known status and reason, but a pair the reservation service never emits.
        RenderedNotification rendered = NotificationRenderer.render(notice(
                "BANK_TRANSFER", "PENDING_PAYMENT", "CONFIRMED", "PAYMENT_DEADLINE_MISSED", "240.00", DEADLINE));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.UNKNOWN);
        assertThat(rendered.text()).isEqualTo(
                "Reservation P4145478 at property AMS01: status changed from PENDING_PAYMENT to CONFIRMED "
                        + "(reason: PAYMENT_DEADLINE_MISSED).");
    }

    @Test
    void unknownStatusValueRendersAsUnknown() {
        RenderedNotification rendered = NotificationRenderer.render(
                notice("CASH", "CONFIRMED", "CHECKED_IN", null, "0.00", null));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.UNKNOWN);
        assertThat(rendered.text()).contains("status changed from CONFIRMED to CHECKED_IN (reason: none)");
    }

    @Test
    void unknownReasonValueRendersAsUnknown() {
        // A reason this service does not know is not treated like "no reason": CONFIRMED + null would be a template.
        RenderedNotification rendered = NotificationRenderer.render(
                notice("CASH", null, "CONFIRMED", "UPGRADED", "0.00", null));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.UNKNOWN);
    }

    @Test
    void pendingReservationWithoutDeadlineRendersAsUnknown() {
        // The bank instructions need the deadline; without one the creation template cannot be filled honestly.
        RenderedNotification rendered = NotificationRenderer.render(
                notice("BANK_TRANSFER", null, "PENDING_PAYMENT", null, "0.00", null));

        assertThat(rendered.template()).isEqualTo(NotificationTemplate.UNKNOWN);
    }

    private static ReservationStatusNotice notice(String paymentMode, @Nullable String previousStatus, String status,
            @Nullable String reason, String amountReceived, @Nullable Instant paymentDeadlineAt) {
        return new ReservationStatusNotice("P4145478", "AMS01", "Ada Lovelace", "201", LocalDate.parse("2027-10-10"),
                LocalDate.parse("2027-10-12"), paymentMode, previousStatus, status, reason, new BigDecimal("240.00"),
                new BigDecimal(amountReceived), "EUR", paymentDeadlineAt, Instant.parse("2026-09-26T10:00:00Z"));
    }
}
