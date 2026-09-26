package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.NativeQuery;

/** Spring Data repository behind {@link JpaReservationRepositoryAdapter}. Package-private: nothing else needs it. */
interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {

    /**
     * ADR-0010's due predicate, written once and spliced into the three native queries below by compile-time
     * constant concatenation, so the three cannot drift apart from each other or from the partial index
     * {@code reservation_deadline_idx} (V1__schema.sql) that this predicate matches exactly.
     */
    String AUTO_CANCEL_DUE_PREDICATE =
            "status = 'PENDING_PAYMENT' AND payment_mode = 'BANK_TRANSFER' AND payment_deadline_at <= :now";

    /** Scoped by property first, so a reservation of another property is simply not found (ADR-0002). */
    Optional<ReservationEntity> findByPropertyIdAndReservationId(String propertyId, String reservationId);

    /**
     * By business id alone, with a row lock ({@code SELECT ... FOR UPDATE}) held until the transaction ends. Payments
     * are matched by the globally unique reservation id, never by property (ADR-0009).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationEntity> findByReservationId(String reservationId);

    /** The exclusion constraint's predicate ({@code reservation_no_overlap}, V1__schema.sql) as a plain read. */
    @NativeQuery("""
            SELECT EXISTS (
              SELECT 1 FROM reservation
               WHERE property_id = :propertyId AND room_number = :roomNumber AND status <> 'CANCELLED'
                 AND stay && daterange(:startDate, :endDate, '[)'))
            """)
    boolean existsOverlapping(String propertyId, String roomNumber, LocalDate startDate, LocalDate endDate);

    /** Deliberately not scoped by property: a payment reference identifies one payment corporation-wide. */
    boolean existsByPaymentModeAndPaymentReference(PaymentMode paymentMode, String paymentReference);

    /**
     * The first page of {@link #AUTO_CANCEL_DUE_PREDICATE} rows, read without any lock (ADR-0010's amendment), a
     * lean projection rather than {@link ReservationEntity} because the caller only needs the keyset to page
     * further and the id to claim each row individually.
     */
    @NativeQuery("SELECT id AS id, payment_deadline_at AS paymentDeadlineAt FROM reservation WHERE "
            + AUTO_CANCEL_DUE_PREDICATE
            + " ORDER BY payment_deadline_at, id LIMIT :limit")
    List<DueRow> findDueFirstPage(Instant now, int limit);

    /**
     * The next page after {@code (afterDeadline, afterId)} in {@code (payment_deadline_at, id)} order — the same
     * row comparison ADR-0010 specifies, so a page never repeats or skips a row even though nothing is locked
     * between one call and the next.
     */
    @NativeQuery("SELECT id AS id, payment_deadline_at AS paymentDeadlineAt FROM reservation WHERE "
            + AUTO_CANCEL_DUE_PREDICATE
            + " AND (payment_deadline_at, id) > (:afterDeadline, :afterId)"
            + " ORDER BY payment_deadline_at, id LIMIT :limit")
    List<DueRow> findDueAfter(Instant now, Instant afterDeadline, UUID afterId, int limit);

    /**
     * Claims one row: locks it and re-checks {@link #AUTO_CANCEL_DUE_PREDICATE} in a single statement.
     * {@code SKIP LOCKED} means a row another transaction already holds is simply absent from the result rather
     * than blocking this one; a full {@link ReservationEntity} (not a projection) so it becomes a managed instance
     * that a later {@code entityManager.find} in the same transaction resolves to, as
     * {@link JpaReservationRepositoryAdapter#update} relies on.
     */
    @NativeQuery("SELECT * FROM reservation WHERE id = :id AND " + AUTO_CANCEL_DUE_PREDICATE + " FOR UPDATE SKIP LOCKED")
    Optional<ReservationEntity> claimIfDue(UUID id, Instant now);

    /** How many rows currently match {@link #AUTO_CANCEL_DUE_PREDICATE} — the {@code reservation.autocancel.overdue} gauge. */
    @NativeQuery("SELECT count(*) FROM reservation WHERE " + AUTO_CANCEL_DUE_PREDICATE)
    long countDue(Instant now);

    /**
     * Interface projection for {@link #findDueFirstPage} / {@link #findDueAfter}: the column aliases above
     * ({@code id}, {@code paymentDeadlineAt}) are matched to these getters by name, so Spring Data proxies this
     * without loading a full {@link ReservationEntity} into the persistence context for rows nothing here mutates.
     */
    interface DueRow {
        UUID getId();

        Instant getPaymentDeadlineAt();
    }
}
