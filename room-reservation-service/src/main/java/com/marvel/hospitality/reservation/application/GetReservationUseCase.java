package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;

/** Reads one reservation of one property. Read-only, so no transaction is opened. */
public class GetReservationUseCase {

    private final PropertyCatalog catalog;
    private final ReservationRepository reservations;

    public GetReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations) {
        this.catalog = catalog;
        this.reservations = reservations;
    }

    /** @throws ReservationNotFoundException also for a malformed id, which cannot exist */
    public ReservationView get(String propertyId, String reservationId) {
        ReservationId id;
        try {
            id = ReservationId.of(reservationId);
        } catch (IllegalArgumentException malformed) {
            throw new ReservationNotFoundException(propertyId, reservationId);
        }
        Reservation reservation = reservations.find(propertyId, id)
                .orElseThrow(() -> new ReservationNotFoundException(propertyId, reservationId));
        Property property = catalog.findProperty(propertyId)
                .orElseThrow(() -> new IllegalStateException("Reservation " + reservationId + " has no property"));
        return new ReservationView(reservation, reservation.bankTransferInstructions(property.bankAccountNumber()));
    }
}
