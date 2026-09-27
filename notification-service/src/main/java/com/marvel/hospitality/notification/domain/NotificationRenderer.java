package com.marvel.hospitality.notification.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Turns a status event into customer text: {@link NotificationTemplate#select selects} the template, then fills a
 * text block with {@link String#formatted}. No template engine: four short messages do not need one.
 *
 * <p>Times are shown in UTC because the event carries no property timezone. The bank instructions cannot name the
 * account to pay into (the event has no bank account; the reservation API's {@code bankTransferInstructions} does),
 * so the text points to the booking confirmation for it.
 */
public final class NotificationRenderer {

    private static final DateTimeFormatter DEADLINE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private NotificationRenderer() {
    }

    public static RenderedNotification render(ReservationStatusNotice notice) {
        NotificationTemplate template = NotificationTemplate.select(notice);
        String text = switch (template) {
            case RESERVATION_CREATED_PENDING_PAYMENT -> """
                    Dear %s,

                    thank you for your reservation %s: room %s at property %s, from %s to %s.
                    Please transfer %s to the property's bank account shown in your booking confirmation, with the \
                    description "<your E2E id> %s", so that it arrives by %s. Partial transfers add up.
                    If the full amount has not arrived by then, the reservation is cancelled automatically.
                    """.formatted(notice.customerName(), notice.reservationId(), notice.roomNumber(),
                    notice.propertyId(), notice.startDate(), notice.endDate(), money(notice.totalAmount(), notice),
                    notice.reservationId(), deadline(notice.paymentDeadlineAt()));
            case RESERVATION_CONFIRMED -> """
                    Dear %s,

                    your reservation %s is confirmed: room %s at property %s, from %s to %s.
                    Total: %s (payment: %s, received so far: %s).
                    """.formatted(notice.customerName(), notice.reservationId(), notice.roomNumber(),
                    notice.propertyId(), notice.startDate(), notice.endDate(), money(notice.totalAmount(), notice),
                    notice.paymentMode(), money(notice.amountReceived(), notice));
            case PARTIAL_PAYMENT_RECEIVED -> """
                    Dear %s,

                    we received a payment for your reservation %s. Received so far: %s of %s.
                    Remaining: %s%s. Your reservation is confirmed as soon as the full amount has arrived.
                    """.formatted(notice.customerName(), notice.reservationId(), money(notice.amountReceived(), notice),
                    money(notice.totalAmount(), notice),
                    money(notice.totalAmount().subtract(notice.amountReceived()), notice),
                    notice.paymentDeadlineAt() == null ? "" : ", due by " + deadline(notice.paymentDeadlineAt()));
            case RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED -> """
                    Dear %s,

                    your reservation %s (room %s at property %s, from %s to %s) has been cancelled because the full \
                    amount of %s did not arrive by the payment deadline%s.
                    Received before the deadline: %s.
                    """.formatted(notice.customerName(), notice.reservationId(), notice.roomNumber(),
                    notice.propertyId(), notice.startDate(), notice.endDate(), money(notice.totalAmount(), notice),
                    notice.paymentDeadlineAt() == null ? "" : " (" + deadline(notice.paymentDeadlineAt()) + ")",
                    money(notice.amountReceived(), notice));
            case UNKNOWN -> """
                    Reservation %s at property %s: status changed from %s to %s (reason: %s).
                    """.formatted(notice.reservationId(), notice.propertyId(),
                    Objects.requireNonNullElse(notice.previousStatus(), "none"), notice.status(),
                    Objects.requireNonNullElse(notice.reason(), "none"));
        };
        return new RenderedNotification(template, text.strip());
    }

    private static String money(BigDecimal amount, ReservationStatusNotice notice) {
        return notice.currency() + " " + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String deadline(Instant instant) {
        return DEADLINE.format(Objects.requireNonNull(instant));
    }
}
