package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.ReservationId;

/** One reservation {@link CancelOverdueReservationUseCase} cancelled — enough to tag the cancellation counter. */
public record CancelledReservation(ReservationId reservationId, String propertyId) {
}
