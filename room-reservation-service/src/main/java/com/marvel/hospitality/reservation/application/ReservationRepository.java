package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import java.util.Optional;

/** Persistence port for the reservation aggregate. Implementations must run inside the caller's transaction. */
public interface ReservationRepository {

    /**
     * Inserts a new reservation and flushes, so constraint violations surface here rather than at commit.
     *
     * @throws RoomUnavailableException the exclusion constraint {@code reservation_no_overlap} rejected the stay
     * @throws ReservationIdCollisionException the business id is already taken (unique index)
     */
    void add(Reservation reservation);

    /** Looks up by property <em>and</em> id, so a reservation of another property is simply not found. */
    Optional<Reservation> find(String propertyId, ReservationId reservationId);
}
