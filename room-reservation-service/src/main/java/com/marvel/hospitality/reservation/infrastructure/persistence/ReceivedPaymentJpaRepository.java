package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository behind {@link JpaReceivedPaymentRepositoryAdapter}. Package-private: nothing else needs it. */
interface ReceivedPaymentJpaRepository extends JpaRepository<ReceivedPaymentEntity, String> {

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM ReceivedPaymentEntity p
             WHERE p.reservationId = :reservationId AND p.outcome IN :outcomes
            """)
    BigDecimal sumAmount(String reservationId, Collection<PaymentMatchOutcome> outcomes);

    List<ReceivedPaymentEntity> findByReservationIdOrderByReceivedAtAscPaymentIdAsc(String reservationId);

    List<ReceivedPaymentEntity> findByReservationIdIsNullOrderByReceivedAtAscPaymentIdAsc();

    List<ReceivedPaymentEntity> findByPropertyIdAndOutcomeOrderByReceivedAtAscPaymentIdAsc(
            String propertyId, PaymentMatchOutcome outcome);
}
