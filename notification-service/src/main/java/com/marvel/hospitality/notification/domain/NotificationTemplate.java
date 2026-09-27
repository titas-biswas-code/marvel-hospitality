package com.marvel.hospitality.notification.domain;

import java.util.Optional;

/**
 * Which customer message a status event becomes, chosen by its {@code (status, reason)} pair (events.md lists every
 * pair the reservation service emits). Anything else — a pair not listed, or a status/reason value this service does
 * not know yet — is {@link #UNKNOWN}: still stored and logged, never rejected, because an event the producer was
 * allowed to send is not a contract violation just because nobody wrote a template for it.
 */
public enum NotificationTemplate {
    /** A bank-transfer reservation was created and waits for its payment (previousStatus and reason are null). */
    RESERVATION_CREATED_PENDING_PAYMENT,
    /** The full amount arrived ({@code PAYMENT_RECEIVED}), or a cash/credit-card reservation was confirmed at booking. */
    RESERVATION_CONFIRMED,
    /** Part of the amount arrived; the status stays {@code PENDING_PAYMENT}. */
    PARTIAL_PAYMENT_RECEIVED,
    /** The payment deadline passed before the full amount arrived. */
    RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED,
    UNKNOWN;

    public static NotificationTemplate select(ReservationStatusNotice notice) {
        Optional<ReservationStatus> status = ReservationStatus.fromWire(notice.status());
        Optional<StatusChangeReason> reason = StatusChangeReason.fromWire(notice.reason());
        if (status.isEmpty() || (notice.reason() != null && reason.isEmpty())) {
            return UNKNOWN;
        }
        boolean creation = notice.previousStatus() == null && notice.reason() == null;
        return switch (status.get()) {
            // The bank instructions need the deadline; a pending reservation without one is not a bank transfer.
            case PENDING_PAYMENT -> creation && notice.paymentDeadlineAt() != null
                    ? RESERVATION_CREATED_PENDING_PAYMENT
                    : is(reason, StatusChangeReason.PARTIAL_PAYMENT_RECEIVED) ? PARTIAL_PAYMENT_RECEIVED : UNKNOWN;
            case CONFIRMED -> creation || is(reason, StatusChangeReason.PAYMENT_RECEIVED)
                    ? RESERVATION_CONFIRMED
                    : UNKNOWN;
            case CANCELLED -> is(reason, StatusChangeReason.PAYMENT_DEADLINE_MISSED)
                    ? RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED
                    : UNKNOWN;
        };
    }

    private static boolean is(Optional<StatusChangeReason> reason, StatusChangeReason expected) {
        return reason.filter(expected::equals).isPresent();
    }
}
