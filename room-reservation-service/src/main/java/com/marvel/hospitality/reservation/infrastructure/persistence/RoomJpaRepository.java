package com.marvel.hospitality.reservation.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository behind {@link JpaPropertyCatalogAdapter}. Package-private: nothing else needs it. */
interface RoomJpaRepository extends JpaRepository<RoomEntity, RoomId> {

    Optional<RoomEntity> findByPropertyIdAndRoomNumber(String propertyId, String roomNumber);
}
