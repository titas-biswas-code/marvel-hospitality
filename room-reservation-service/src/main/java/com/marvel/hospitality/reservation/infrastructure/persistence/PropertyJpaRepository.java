package com.marvel.hospitality.reservation.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository behind {@link JpaPropertyCatalogAdapter}. Package-private: nothing else needs it. */
interface PropertyJpaRepository extends JpaRepository<PropertyEntity, String> {
}
