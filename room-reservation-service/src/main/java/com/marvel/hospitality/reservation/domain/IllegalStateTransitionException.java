package com.marvel.hospitality.reservation.domain;

/**
 * An attempt to move a {@link Reservation} between statuses that ADR-0004's transition table does not allow
 * (e.g. confirming an already-cancelled reservation). The aggregate is left completely unchanged: no field
 * mutation, no event recorded.
 */
public class IllegalStateTransitionException extends DomainException {

    private final ReservationStatus from;
    private final ReservationStatus to;

    public IllegalStateTransitionException(ReservationStatus from, ReservationStatus to) {
        super("Cannot transition reservation from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public ReservationStatus from() {
        return from;
    }

    public ReservationStatus to() {
        return to;
    }
}
