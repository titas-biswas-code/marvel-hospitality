package com.marvel.hospitality.reservation.application;

/** The generated business id already exists (unique index); {@link CreateReservationUseCase} retries with a new one. */
public class ReservationIdCollisionException extends RuntimeException {

    public ReservationIdCollisionException(String reservationId, Throwable cause) {
        super("Reservation id " + reservationId + " is already taken.", cause);
    }
}
