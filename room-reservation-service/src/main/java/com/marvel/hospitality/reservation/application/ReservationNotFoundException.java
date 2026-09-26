package com.marvel.hospitality.reservation.application;

/**
 * {@code 404 RESERVATION_NOT_FOUND}, also when the id exists under another property: the caller learns nothing
 * about other properties' reservations (rest-api.md).
 */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(String propertyId, String reservationId) {
        super("Reservation " + reservationId + " does not exist in property " + propertyId + ".");
    }
}
