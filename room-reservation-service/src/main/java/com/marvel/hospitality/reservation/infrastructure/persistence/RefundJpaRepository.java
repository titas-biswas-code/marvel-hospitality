package com.marvel.hospitality.reservation.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository behind {@link JpaRefundRepositoryAdapter}. Package-private: nothing else needs it. */
interface RefundJpaRepository extends JpaRepository<RefundEntity, UUID> {

    List<RefundEntity> findByPaymentIdIn(Collection<String> paymentIds);
}
