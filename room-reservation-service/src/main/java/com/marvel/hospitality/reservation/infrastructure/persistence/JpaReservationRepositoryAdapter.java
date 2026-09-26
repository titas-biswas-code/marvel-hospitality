package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.platform.problem.ConstraintNames;
import com.marvel.hospitality.reservation.application.ReservationIdCollisionException;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.application.RoomUnavailableException;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link ReservationRepository} on top of JPA/Postgres. {@link #add} inserts through the raw {@link EntityManager}
 * rather than {@code ReservationJpaRepository.save}: the aggregate's {@link java.util.UUID} id is always
 * client-assigned (never generated), and {@code save()} would have to guess "is this new?" from that id alone,
 * risking a spurious {@code SELECT}-then-merge; {@code persist()} always inserts.
 *
 * <p>Flushing inside {@code add} turns the DB's two hard-won guarantees (ADR-0005's exclusion constraint,
 * {@code reservation.reservation_id}'s unique index) into exceptions the caller sees immediately, in the same
 * transaction, rather than at an unrelated later flush point. Both are mapped by constraint name, never by
 * blanket-mapping {@code DataIntegrityViolationException} (ADR-0005): anything else is rethrown as-is
 * so a FK or CHECK violation is never mistaken for room unavailability or an id collision.
 */
@Repository
class JpaReservationRepositoryAdapter implements ReservationRepository {

    // Names as Postgres actually assigns them; see V1__schema.sql. Verified against a real database in
    // ReservationPersistenceTest, never guessed, per ADR-0005's insistence on mapping by constraint name.
    static final String OVERLAP_CONSTRAINT = "reservation_no_overlap";
    // Postgres's default name for an inline `UNIQUE` column constraint: "<table>_<column>_key".
    static final String RESERVATION_ID_UNIQUE_CONSTRAINT = "reservation_reservation_id_key";

    private final EntityManager entityManager;
    private final ReservationJpaRepository jpaRepository;

    JpaReservationRepositoryAdapter(EntityManager entityManager, ReservationJpaRepository jpaRepository) {
        this.entityManager = entityManager;
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void add(Reservation reservation) {
        ReservationEntity entity = ReservationEntity.fromDomain(reservation.snapshot());
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (RuntimeException ex) {
            Optional<String> constraint = ConstraintNames.of(ex);
            if (constraint.isPresent() && OVERLAP_CONSTRAINT.equals(constraint.get())) {
                throw new RoomUnavailableException(reservation.propertyId(), reservation.roomNumber(), ex);
            }
            if (constraint.isPresent() && RESERVATION_ID_UNIQUE_CONSTRAINT.equals(constraint.get())) {
                throw new ReservationIdCollisionException(reservation.reservationId().value(), ex);
            }
            throw ex;
        }
    }

    @Override
    public Optional<Reservation> find(String propertyId, ReservationId reservationId) {
        return jpaRepository.findByPropertyIdAndReservationId(propertyId, reservationId.value())
                .map(entity -> Reservation.rehydrate(entity.toDomainState()));
    }
}
