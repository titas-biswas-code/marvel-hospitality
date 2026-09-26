package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.application.OverdueCandidate;
import com.marvel.hospitality.reservation.application.OverduePaymentReservationQuery;
import com.marvel.hospitality.reservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * {@link OverduePaymentReservationQuery} on top of {@link ReservationJpaRepository}'s native queries (ADR-0010).
 * {@link #claimIfDue} returns a managed {@link ReservationEntity}, so a later {@code entityManager.find} inside the
 * same transaction — {@link JpaReservationRepositoryAdapter#update} does exactly that — resolves to the very same
 * persistence-context instance rather than issuing a second {@code SELECT}.
 */
@Repository
class JpaOverduePaymentReservationQueryAdapter implements OverduePaymentReservationQuery {

    private final ReservationJpaRepository jpaRepository;

    JpaOverduePaymentReservationQueryAdapter(ReservationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<OverdueCandidate> findDue(Instant now, @Nullable OverdueCandidate after, int limit) {
        List<ReservationJpaRepository.DueRow> rows = after == null
                ? jpaRepository.findDueFirstPage(now, limit)
                : jpaRepository.findDueAfter(now, after.paymentDeadlineAt(), after.id(), limit);
        return rows.stream().map(row -> new OverdueCandidate(row.getId(), row.getPaymentDeadlineAt())).toList();
    }

    @Override
    public Optional<Reservation> claimIfDue(UUID id, Instant now) {
        return jpaRepository.claimIfDue(id, now).map(entity -> Reservation.rehydrate(entity.toDomainState()));
    }

    @Override
    public long countDue(Instant now) {
        return jpaRepository.countDue(now);
    }
}
