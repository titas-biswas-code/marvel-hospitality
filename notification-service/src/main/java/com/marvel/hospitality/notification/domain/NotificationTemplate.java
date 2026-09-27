package com.marvel.hospitality.notification.domain;

import static com.marvel.hospitality.notification.domain.PaymentMode.BANK_TRANSFER;
import static com.marvel.hospitality.notification.domain.PaymentMode.CASH;
import static com.marvel.hospitality.notification.domain.PaymentMode.CREDIT_CARD;
import static com.marvel.hospitality.notification.domain.ReservationStatus.CANCELLED;
import static com.marvel.hospitality.notification.domain.ReservationStatus.CONFIRMED;
import static com.marvel.hospitality.notification.domain.ReservationStatus.PENDING_PAYMENT;
import static com.marvel.hospitality.notification.domain.StatusChangeReason.PAYMENT_DEADLINE_MISSED;
import static com.marvel.hospitality.notification.domain.StatusChangeReason.PAYMENT_RECEIVED;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Which customer message a status event becomes. The choice is a lookup in {@link #TRANSITIONS}: one row per
 * {@code (previousStatus, status, reason, paymentMode)} combination the reservation service emits (events.md "Emitted
 * on", and its payment-mode handlers for what a creation event looks like). Every other combination — including a
 * status, reason or mode value this service does not know yet — is {@link #UNKNOWN}: stored and logged, never rejected,
 * because an event the producer was allowed to send is not a contract violation just because nobody wrote a template
 * for it.
 */
public enum NotificationTemplate {
    /** A bank-transfer reservation was created and waits for its payment. */
    RESERVATION_CREATED_PENDING_PAYMENT,
    /** Confirmed: a cash or credit-card reservation at booking, or a bank transfer once the full amount arrived. */
    RESERVATION_CONFIRMED,
    /** Part of a bank transfer arrived; the status stays {@code PENDING_PAYMENT}. */
    PARTIAL_PAYMENT_RECEIVED,
    /** The payment deadline passed before the full bank transfer arrived. */
    RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED,
    UNKNOWN;

    /** One status transition as the event describes it; {@code null} components are the event's {@code null}s. */
    record Transition(@Nullable ReservationStatus previousStatus, ReservationStatus status,
            @Nullable StatusChangeReason reason, PaymentMode paymentMode) {
    }

    /**
     * Creation events carry {@code previousStatus = null} and {@code reason = null}; the payment mode decides their
     * status (cash and card are confirmed at once, a bank transfer waits for its money).
     */
    static final Map<Transition, NotificationTemplate> TRANSITIONS = Map.of(
            new Transition(null, PENDING_PAYMENT, null, BANK_TRANSFER), RESERVATION_CREATED_PENDING_PAYMENT,
            new Transition(null, CONFIRMED, null, CASH), RESERVATION_CONFIRMED,
            new Transition(null, CONFIRMED, null, CREDIT_CARD), RESERVATION_CONFIRMED,
            new Transition(PENDING_PAYMENT, CONFIRMED, PAYMENT_RECEIVED, BANK_TRANSFER), RESERVATION_CONFIRMED,
            // Qualified: inside this enum the bare name is the template constant, not the reason.
            new Transition(PENDING_PAYMENT, PENDING_PAYMENT, StatusChangeReason.PARTIAL_PAYMENT_RECEIVED, BANK_TRANSFER),
            PARTIAL_PAYMENT_RECEIVED,
            new Transition(PENDING_PAYMENT, CANCELLED, PAYMENT_DEADLINE_MISSED, BANK_TRANSFER),
            RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED);

    public static NotificationTemplate select(ReservationStatusNotice notice) {
        return transition(notice).map(t -> TRANSITIONS.getOrDefault(t, UNKNOWN)).orElse(UNKNOWN);
    }

    /** @return the event's transition, empty when any of its values is not one this service knows */
    private static Optional<Transition> transition(ReservationStatusNotice notice) {
        Optional<ReservationStatus> previous = ReservationStatus.fromWire(notice.previousStatus());
        Optional<ReservationStatus> status = ReservationStatus.fromWire(notice.status());
        Optional<StatusChangeReason> reason = StatusChangeReason.fromWire(notice.reason());
        Optional<PaymentMode> mode = PaymentMode.fromWire(notice.paymentMode());
        boolean unknownValue = (notice.previousStatus() != null && previous.isEmpty())
                || (notice.reason() != null && reason.isEmpty());
        if (unknownValue || status.isEmpty() || mode.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Transition(previous.orElse(null), status.get(), reason.orElse(null), mode.get()));
    }
}
