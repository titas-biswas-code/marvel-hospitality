package com.marvel.hospitality.reservation.application;

/**
 * {@code 409 ROOM_UNAVAILABLE}: the database's exclusion constraint {@code reservation_no_overlap} rejected the
 * stay (SQLSTATE 23P01). The constraint is the single source of truth for availability (ADR-0005).
 */
public class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException(String propertyId, String roomNumber, Throwable cause) {
        super("Room " + roomNumber + " in property " + propertyId + " is already booked for some of these nights.", cause);
    }
}
