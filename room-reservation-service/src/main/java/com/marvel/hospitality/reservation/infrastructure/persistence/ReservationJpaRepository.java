package com.marvel.hospitality.reservation.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository behind {@link JpaReservationRepositoryAdapter}. Package-private: nothing else needs it. */
interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {

    /** Scoped by property first, so a reservation of another property is simply not found (ADR-0002). */
    Optional<ReservationEntity> findByPropertyIdAndReservationId(String propertyId, String reservationId);
}
