package com.marvel.hospitality.reservation.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The reservation aggregate. The state machine (ADR-0004) is:
 * <pre>
 * (new)           -&gt; PENDING_PAYMENT   bank transfer created
 * (new)           -&gt; CONFIRMED         cash, or credit card confirmed
 * PENDING_PAYMENT -&gt; CONFIRMED         full amount received
 * PENDING_PAYMENT -&gt; CANCELLED         payment deadline missed
 * anything else   -&gt; IllegalStateTransitionException
 * </pre>
 * {@link #confirm}, {@link #cancel} and {@link #recordPayment} are the only mutators; {@link #confirm} and
 * {@link #cancel} each record a {@link ReservationStatusChanged} domain event that the application layer
 * turns into an outbox row in the same transaction. No Spring Statemachine: three states and four transitions
 * do not justify a framework.
 */
public final class Reservation {

    /** The transition table above, made explicit and checkable rather than scattered across if-statements. */
    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED = Map.of(
            ReservationStatus.PENDING_PAYMENT, Set.of(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED),
            ReservationStatus.CONFIRMED, Set.of(),
            ReservationStatus.CANCELLED, Set.of());

    private final UUID id;
    private final ReservationId reservationId;
    private final String propertyId;
    private final String roomNumber;
    private final String customerName;
    private final StayPeriod stay;
    private final RoomSegment roomSegment;
    private final PaymentMode paymentMode;
    private final @Nullable String paymentReference;
    private final Money totalAmount;
    private final @Nullable Instant paymentDeadlineAt;
    private final long version;
    private final Instant createdAt;
    private final List<ReservationStatusChanged> events = new ArrayList<>();

    private ReservationStatus status;
    private @Nullable CancellationReason cancellationReason;
    private Money amountReceived;
    private Instant updatedAt;

    private Reservation(
            UUID id,
            ReservationId reservationId,
            String propertyId,
            String roomNumber,
            String customerName,
            StayPeriod stay,
            RoomSegment roomSegment,
            PaymentMode paymentMode,
            @Nullable String paymentReference,
            ReservationStatus status,
            @Nullable CancellationReason cancellationReason,
            Money totalAmount,
            Money amountReceived,
            @Nullable Instant paymentDeadlineAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.reservationId = reservationId;
        this.propertyId = propertyId;
        this.roomNumber = roomNumber;
        this.customerName = customerName;
        this.stay = stay;
        this.roomSegment = roomSegment;
        this.paymentMode = paymentMode;
        this.paymentReference = paymentReference;
        this.status = status;
        this.cancellationReason = cancellationReason;
        this.totalAmount = totalAmount;
        this.amountReceived = amountReceived;
        this.paymentDeadlineAt = paymentDeadlineAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * The creation-time rules that do not depend on the payment mode: stay shape and "not in the past"
     * ({@link StayPeriod#forNewBooking}), room belongs to the property, requested segment matches the room's actual
     * segment. {@link #create} runs them itself; the application layer also runs them <em>before</em> a remote
     * payment check (ADR-0011), so a request that is bound to fail never reaches the payment service.
     *
     * @return the validated stay
     */
    public static StayPeriod checkCreatable(LocalDate startDate, LocalDate endDate, RoomSegment requestedSegment,
            Property property, Room room, Clock clock) {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(clock, "clock");
        StayPeriod stay = StayPeriod.forNewBooking(startDate, endDate, property.today(clock));
        if (!room.propertyId().equals(property.id())) {
            throw new IllegalArgumentException(
                    "Room " + room.roomNumber() + " does not belong to property " + property.id());
        }
        if (requestedSegment != room.segment()) {
            throw new RoomSegmentMismatchException(requestedSegment, room.segment());
        }
        return stay;
    }

    /**
     * Creates a new reservation, running every creation-time rule in order: {@link #checkCreatable}, the handler is
     * the one registered for the requested mode, then hands off to the {@link PaymentModeHandler} to decide the
     * initial status (and, for bank transfer, the payment deadline).
     */
    public static Reservation create(
            NewReservation request, Property property, Room room, Money nightlyRate, PaymentModeHandler handler, Clock clock) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(nightlyRate, "nightlyRate");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(clock, "clock");

        StayPeriod stay = checkCreatable(
                request.startDate(), request.endDate(), request.requestedSegment(), property, room, clock);
        if (handler.mode() != request.paymentMode()) {
            throw new IllegalArgumentException(
                    "Handler for " + handler.mode() + " cannot handle requested mode " + request.paymentMode());
        }

        Money total = nightlyRate.times(stay.nights());
        Instant now = Instant.now(clock);
        InitialOutcome outcome = handler.handle(new PaymentModeContext(property, stay, total, now));

        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                request.reservationId(),
                property.id(),
                room.roomNumber(),
                request.customerName(),
                stay,
                room.segment(),
                request.paymentMode(),
                request.paymentReference(),
                outcome.status(),
                null,
                total,
                Money.zeroEur(),
                outcome.paymentDeadlineAt(),
                0L,
                now,
                now);
        reservation.recordEvent(null, outcome.status(), null, now);
        return reservation;
    }

    /** {@code PENDING_PAYMENT -> CONFIRMED}, recorded with reason {@code PAYMENT_RECEIVED}. */
    public void confirm(Clock clock) {
        transition(ReservationStatus.CONFIRMED, null, StatusChangeReason.PAYMENT_RECEIVED, clock);
    }

    /** {@code PENDING_PAYMENT -> CANCELLED}, recorded with the {@link StatusChangeReason} matching {@code reason}. */
    public void cancel(CancellationReason reason, Clock clock) {
        Objects.requireNonNull(reason, "reason");
        transition(ReservationStatus.CANCELLED, reason, statusChangeReasonFor(reason), clock);
    }

    /**
     * Bookkeeping only in this PR: matching outcomes and the resulting events/status changes arrive in PR-05
     * (ADR-0009). This just accumulates {@code amountReceived}; it never changes status and never records an
     * event, because "money arrived" and "what that means for the reservation" are deliberately separated.
     */
    public void recordPayment(Money amount, Clock clock) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (status != ReservationStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Cannot record a payment for a reservation in status " + status);
        }
        this.amountReceived = amountReceived.plus(amount);
        this.updatedAt = Instant.now(clock);
    }

    /** Returns and clears the events recorded since the last call — the application layer's outbox source. */
    public List<ReservationStatusChanged> pullEvents() {
        List<ReservationStatusChanged> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    /**
     * The remittance instructions shown to the guest (rest-api.md), {@code null} unless {@link PaymentMode#BANK_TRANSFER}.
     */
    public @Nullable String bankTransferInstructions(String bankAccountNumber) {
        if (paymentMode != PaymentMode.BANK_TRANSFER) {
            return null;
        }
        return "Transfer %s %s to %s with description '<your E2E id> %s'"
                .formatted(totalAmount.amount().toPlainString(), totalAmount.currency(), bankAccountNumber, reservationId);
    }

    /** Rebuilds a reservation from a persisted snapshot. No events are recorded — this is not a state change. */
    public static Reservation rehydrate(ReservationState state) {
        Objects.requireNonNull(state, "state");
        return new Reservation(
                state.id(),
                state.reservationId(),
                state.propertyId(),
                state.roomNumber(),
                state.customerName(),
                state.stay(),
                state.roomSegment(),
                state.paymentMode(),
                state.paymentReference(),
                state.status(),
                state.cancellationReason(),
                state.totalAmount(),
                state.amountReceived(),
                state.paymentDeadlineAt(),
                state.version(),
                state.createdAt(),
                state.updatedAt());
    }

    /** The flat snapshot infrastructure persists. */
    public ReservationState snapshot() {
        return new ReservationState(
                id,
                reservationId,
                propertyId,
                roomNumber,
                customerName,
                stay,
                roomSegment,
                paymentMode,
                paymentReference,
                status,
                cancellationReason,
                totalAmount,
                amountReceived,
                paymentDeadlineAt,
                version,
                createdAt,
                updatedAt);
    }

    public UUID id() {
        return id;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public String propertyId() {
        return propertyId;
    }

    public String roomNumber() {
        return roomNumber;
    }

    public String customerName() {
        return customerName;
    }

    public StayPeriod stay() {
        return stay;
    }

    public RoomSegment roomSegment() {
        return roomSegment;
    }

    public PaymentMode paymentMode() {
        return paymentMode;
    }

    public @Nullable String paymentReference() {
        return paymentReference;
    }

    public ReservationStatus status() {
        return status;
    }

    public @Nullable CancellationReason cancellationReason() {
        return cancellationReason;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public Money amountReceived() {
        return amountReceived;
    }

    public @Nullable Instant paymentDeadlineAt() {
        return paymentDeadlineAt;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private void transition(ReservationStatus to, @Nullable CancellationReason cancellationReason, StatusChangeReason reason, Clock clock) {
        if (!ALLOWED.getOrDefault(status, Set.of()).contains(to)) {
            throw new IllegalStateTransitionException(status, to);
        }
        ReservationStatus previous = status;
        Instant now = Instant.now(clock);
        this.status = to;
        this.cancellationReason = cancellationReason;
        this.updatedAt = now;
        recordEvent(previous, to, reason, now);
    }

    private void recordEvent(@Nullable ReservationStatus previousStatus, ReservationStatus newStatus, @Nullable StatusChangeReason reason, Instant occurredAt) {
        events.add(new ReservationStatusChanged(
                reservationId,
                propertyId,
                customerName,
                roomNumber,
                stay.startDate(),
                stay.endDate(),
                paymentMode,
                previousStatus,
                newStatus,
                reason,
                totalAmount,
                amountReceived,
                paymentDeadlineAt,
                occurredAt));
    }

    private static StatusChangeReason statusChangeReasonFor(CancellationReason reason) {
        return switch (reason) {
            case PAYMENT_DEADLINE_MISSED -> StatusChangeReason.PAYMENT_DEADLINE_MISSED;
        };
    }
}
