package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.application.ReceivedPaymentRepository;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.ReservationId;
import jakarta.persistence.EntityManager;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * {@link ReceivedPaymentRepository} on top of JPA/Postgres. {@link #add} persists and flushes, like the reservation
 * adapter: the payment id is assigned by the payment service, and an insert failure should surface inside the use
 * case, not at commit. A second row with the same {@code payment_id} cannot happen in practice, because the inbox
 * row for that id is written first in the same transaction.
 */
@Repository
class JpaReceivedPaymentRepositoryAdapter implements ReceivedPaymentRepository {

    private static final Set<PaymentMatchOutcome> MATCHED = EnumSet.of(
            PaymentMatchOutcome.MATCHED_PARTIAL, PaymentMatchOutcome.MATCHED_FULL, PaymentMatchOutcome.OVERPAID);

    private final EntityManager entityManager;
    private final ReceivedPaymentJpaRepository jpaRepository;

    JpaReceivedPaymentRepositoryAdapter(EntityManager entityManager, ReceivedPaymentJpaRepository jpaRepository) {
        this.entityManager = entityManager;
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void add(ReceivedPayment payment) {
        entityManager.persist(ReceivedPaymentEntity.fromDomain(payment));
        entityManager.flush();
    }

    @Override
    public Money sumMatched(ReservationId reservationId) {
        return Money.eur(jpaRepository.sumAmount(reservationId.value(), MATCHED));
    }

    @Override
    public List<ReceivedPayment> findByReservation(ReservationId reservationId) {
        return toDomain(jpaRepository.findByReservationIdOrderByReceivedAtAscPaymentIdAsc(reservationId.value()));
    }

    @Override
    public List<ReceivedPayment> findWithoutReservation() {
        return toDomain(jpaRepository.findByReservationIdIsNullOrderByReceivedAtAscPaymentIdAsc());
    }

    @Override
    public List<ReceivedPayment> findNotPending(String propertyId) {
        return toDomain(jpaRepository.findByPropertyIdAndOutcomeOrderByReceivedAtAscPaymentIdAsc(
                propertyId, PaymentMatchOutcome.UNMATCHED_NOT_PENDING));
    }

    private static List<ReceivedPayment> toDomain(List<ReceivedPaymentEntity> entities) {
        return entities.stream().map(ReceivedPaymentEntity::toDomain).toList();
    }
}
