package com.marvel.hospitality.reservation.application;

/**
 * {@code 409 ROOM_UNAVAILABLE}: the database's exclusion constraint {@code reservation_no_overlap} rejected the
 * stay (SQLSTATE 23P01). The constraint is the single source of truth for availability (ADR-0005); the lock-free
 * pre-check before a remote payment call ({@link ReservationRepository#isBooked}) only avoids a pointless call.
 */
public class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException(String propertyId, String roomNumber, Throwable cause) {
        super(message(propertyId, roomNumber), cause);
    }

    public RoomUnavailableException(String propertyId, String roomNumber) {
        super(message(propertyId, roomNumber));
    }

    private static String message(String propertyId, String roomNumber) {
        return "Room " + roomNumber + " in property " + propertyId + " is already booked for some of these nights.";
    }
}
