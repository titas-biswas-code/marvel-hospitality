package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Money this hotel owes back for one payment: the surplus of an overpayment, or the whole of a payment that arrived
 * after its reservation stopped awaiting payment (ADR-0009). It is the compensating action of the booking saga
 * (ADR-0006) and has its own small state machine:
 * <pre>
 * (new)     -&gt; REQUESTED   records RefundRequested, sent to the payment service
 * REQUESTED -&gt; COMPLETED   the payment service paid it out
 * REQUESTED -&gt; FAILED      the payment service could not pay it out; a human has to act
 * </pre>
 * A payment has at most one refund, so the refund carries its {@code paymentId} and nothing links back.
 */
public final class Refund {

    private final UUID refundId;
    private final String paymentId;
    private final ReservationId reservationId;
    private final String propertyId;
    private final Money amount;
    private final RefundReason reason;
    private final Instant requestedAt;
    private final List<RefundRequested> events = new ArrayList<>();

    private RefundStatus status;
    private @Nullable String failureReason;
    private @Nullable Instant completedAt;

    private Refund(UUID refundId, String paymentId, ReservationId reservationId, String propertyId, Money amount,
            RefundReason reason, RefundStatus status, @Nullable String failureReason, Instant requestedAt,
            @Nullable Instant completedAt) {
        this.refundId = Objects.requireNonNull(refundId, "refundId");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.propertyId = Objects.requireNonNull(propertyId, "propertyId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.status = Objects.requireNonNull(status, "status");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.failureReason = failureReason;
        this.completedAt = completedAt;
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("refund amount must be positive: " + amount);
        }
        if ((status == RefundStatus.FAILED) != (failureReason != null)) {
            throw new IllegalArgumentException("failureReason must be set iff status is FAILED, got " + status);
        }
        if ((status == RefundStatus.REQUESTED) != (completedAt == null)) {
            throw new IllegalArgumentException("completedAt must be null iff status is REQUESTED, got " + status);
        }
    }

    /**
     * Requests the refund {@link PaymentMatcher} found due for {@code payment} and records {@link RefundRequested}.
     *
     * @throws IllegalArgumentException the payment is not linked to a reservation: unmatched payments are never
     *     refunded automatically (ADR-0009)
     */
    public static Refund request(UUID refundId, ReceivedPayment payment, RefundDue due, Instant requestedAt) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(due, "due");
        ReservationId reservationId = payment.reservationId();
        String propertyId = payment.propertyId();
        if (reservationId == null || propertyId == null) {
            throw new IllegalArgumentException(
                    "payment " + payment.paymentId() + " is not linked to a reservation and is never refunded");
        }
        Refund refund = new Refund(refundId, payment.paymentId(), reservationId, propertyId, due.amount(),
                due.reason(), RefundStatus.REQUESTED, null, requestedAt, null);
        refund.events.add(new RefundRequested(
                refundId, payment.paymentId(), reservationId, propertyId, due.amount(), due.reason(), requestedAt));
        return refund;
    }

    /** Rebuilds a refund from its persisted columns. No events are recorded — this is not a state change. */
    public static Refund rehydrate(UUID refundId, String paymentId, ReservationId reservationId, String propertyId,
            Money amount, RefundReason reason, RefundStatus status, @Nullable String failureReason,
            Instant requestedAt, @Nullable Instant completedAt) {
        return new Refund(refundId, paymentId, reservationId, propertyId, amount, reason, status, failureReason,
                requestedAt, completedAt);
    }

    /** @throws IllegalStateException the refund is no longer {@code REQUESTED} */
    public void complete(Instant completedAt) {
        finish(RefundStatus.COMPLETED, null, completedAt);
    }

    /** @throws IllegalStateException the refund is no longer {@code REQUESTED} */
    public void fail(String failureReason, Instant completedAt) {
        Objects.requireNonNull(failureReason, "failureReason");
        finish(RefundStatus.FAILED, failureReason, completedAt);
    }

    private void finish(RefundStatus outcome, @Nullable String failureReason, Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("refund " + refundId + " is already " + status);
        }
        this.status = outcome;
        this.failureReason = failureReason;
        this.completedAt = completedAt;
    }

    /** Returns and clears the events recorded since the last call — the application layer's outbox source. */
    public List<RefundRequested> pullEvents() {
        List<RefundRequested> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public UUID refundId() {
        return refundId;
    }

    public String paymentId() {
        return paymentId;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public String propertyId() {
        return propertyId;
    }

    public Money amount() {
        return amount;
    }

    public RefundReason reason() {
        return reason;
    }

    public RefundStatus status() {
        return status;
    }

    public @Nullable String failureReason() {
        return failureReason;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public @Nullable Instant completedAt() {
        return completedAt;
    }
}
