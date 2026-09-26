package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository behind {@link JpaPropertyCatalogAdapter}. Package-private: nothing else needs it. */
interface RoomRateJpaRepository extends JpaRepository<RoomRateEntity, RoomRateId> {

    Optional<RoomRateEntity> findByPropertyIdAndSegment(String propertyId, RoomSegment segment);
}
