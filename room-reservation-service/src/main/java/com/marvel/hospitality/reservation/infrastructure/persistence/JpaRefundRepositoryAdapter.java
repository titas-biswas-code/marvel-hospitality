package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.application.RefundRepository;
import com.marvel.hospitality.reservation.domain.Refund;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * {@link RefundRepository} on top of JPA/Postgres. {@link #add} persists and flushes, like the payment adapter: the
 * refund id is client-assigned, and an insert failure should surface inside the use case, not at commit.
 */
@Repository
class JpaRefundRepositoryAdapter implements RefundRepository {

    private final EntityManager entityManager;
    private final RefundJpaRepository jpaRepository;

    JpaRefundRepositoryAdapter(EntityManager entityManager, RefundJpaRepository jpaRepository) {
        this.entityManager = entityManager;
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void add(Refund refund) {
        entityManager.persist(RefundEntity.fromDomain(refund));
        entityManager.flush();
    }

    @Override
    public Optional<Refund> findById(UUID refundId) {
        return jpaRepository.findById(refundId).map(RefundEntity::toDomain);
    }

    @Override
    public void update(Refund refund) {
        RefundEntity entity = entityManager.find(RefundEntity.class, refund.refundId());
        if (entity == null) {
            throw new IllegalStateException("refund " + refund.refundId() + " is not stored");
        }
        entity.applyOutcome(refund);
        entityManager.flush();
    }

    @Override
    public List<Refund> findByPaymentIds(Collection<String> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByPaymentIdIn(paymentIds).stream().map(RefundEntity::toDomain).toList();
    }
}
