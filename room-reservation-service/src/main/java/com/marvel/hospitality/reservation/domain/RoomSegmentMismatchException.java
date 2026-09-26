package com.marvel.hospitality.reservation.domain;

/**
 * The requested {@link RoomSegment} does not match the room's actual, seeded segment — e.g. asking for
 * {@code MEDIUM} on a room that is {@code SMALL}. Maps to {@code 422 ROOM_SEGMENT_MISMATCH} (rest-api.md).
 */
public class RoomSegmentMismatchException extends DomainException {

    private final RoomSegment requested;
    private final RoomSegment actual;

    public RoomSegmentMismatchException(RoomSegment requested, RoomSegment actual) {
        super("Requested segment " + requested + " does not match room segment " + actual);
        this.requested = requested;
        this.actual = actual;
    }

    public RoomSegment requested() {
        return requested;
    }

    public RoomSegment actual() {
        return actual;
    }
}
