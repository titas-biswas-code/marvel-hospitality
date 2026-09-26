package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.CancellationReason;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationState;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import com.marvel.hospitality.reservation.domain.StayPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * JPA mapping of {@code reservation} (database-schemas.md). Maps every column except {@code stay}, the generated
 * {@code daterange} that only the exclusion constraint (ADR-0005) reads — Hibernate has no native type for it and
 * the application never queries it — and treats {@code nights}, the other generated column, as read-only.
 *
 * <p>No {@link org.springframework.data.domain.Persistable}: {@link JpaReservationRepositoryAdapter#add} always
 * calls {@code EntityManager.persist} directly on a brand-new instance, never {@code JpaRepository.save}, so there
 * is no {@code save()}-driven "is this new?" check that could mistake an assigned {@link UUID} id for an existing
 * row and merge (with its extra {@code SELECT}) instead of insert.
 */
@Entity
@Table(name = "reservation")
class ReservationEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private String reservationId;

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    @Column(name = "room_number", nullable = false)
    private String roomNumber;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // Generated column (end_date - start_date); Postgres computes it, this field is never written.
    @Column(name = "nights", insertable = false, updatable = false)
    private int nights;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_segment", nullable = false)
    private RoomSegment roomSegment;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false)
    private PaymentMode paymentMode;

    @Column(name = "payment_reference")
    private @Nullable String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason")
    private @Nullable CancellationReason cancellationReason;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "amount_received", nullable = false)
    private BigDecimal amountReceived;

    // char(3) in the schema (ISO 4217); Hibernate would otherwise validate against varchar.
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "payment_deadline_at")
    private @Nullable Instant paymentDeadlineAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReservationEntity() {
        // JPA
    }

    private ReservationEntity(
            UUID id,
            String reservationId,
            String propertyId,
            String roomNumber,
            String customerName,
            LocalDate startDate,
            LocalDate endDate,
            RoomSegment roomSegment,
            PaymentMode paymentMode,
            @Nullable String paymentReference,
            ReservationStatus status,
            @Nullable CancellationReason cancellationReason,
            BigDecimal totalAmount,
            BigDecimal amountReceived,
            String currency,
            @Nullable Instant paymentDeadlineAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.reservationId = reservationId;
        this.propertyId = propertyId;
        this.roomNumber = roomNumber;
        this.customerName = customerName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.roomSegment = roomSegment;
        this.paymentMode = paymentMode;
        this.paymentReference = paymentReference;
        this.status = status;
        this.cancellationReason = cancellationReason;
        this.totalAmount = totalAmount;
        this.amountReceived = amountReceived;
        this.currency = currency;
        this.paymentDeadlineAt = paymentDeadlineAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Maps a {@link ReservationState} snapshot ({@link com.marvel.hospitality.reservation.domain.Reservation#snapshot}) to a new entity to insert. */
    static ReservationEntity fromDomain(ReservationState state) {
        Money total = state.totalAmount();
        Money received = state.amountReceived();
        return new ReservationEntity(
                state.id(),
                state.reservationId().value(),
                state.propertyId(),
                state.roomNumber(),
                state.customerName(),
                state.stay().startDate(),
                state.stay().endDate(),
                state.roomSegment(),
                state.paymentMode(),
                state.paymentReference(),
                state.status(),
                state.cancellationReason(),
                total.amount(),
                received.amount(),
                total.currency(),
                state.paymentDeadlineAt(),
                state.version(),
                state.createdAt(),
                state.updatedAt());
    }

    /**
     * Copies the fields a reservation can change after creation (status, cancellation reason, amount received,
     * updated-at) onto this managed row. Everything else is immutable once booked. The snapshot must come from this
     * row's current version; Hibernate then increments {@code version} on flush.
     *
     * @throws OptimisticLockException the snapshot was taken from an older version of the row
     */
    void applyChanges(ReservationState state) {
        if (!id.equals(state.id())) {
            throw new IllegalArgumentException("Snapshot of reservation " + state.id() + " applied to row " + id);
        }
        if (version != state.version()) {
            throw new OptimisticLockException("Reservation " + reservationId + " changed since it was loaded: version "
                    + state.version() + " vs " + version);
        }
        this.status = state.status();
        this.cancellationReason = state.cancellationReason();
        this.amountReceived = state.amountReceived().amount();
        this.updatedAt = state.updatedAt();
    }

    /** Maps this row back to a {@link ReservationState}, the shape {@link com.marvel.hospitality.reservation.domain.Reservation#rehydrate} accepts. */
    ReservationState toDomainState() {
        return new ReservationState(
                id,
                ReservationId.of(reservationId),
                propertyId,
                roomNumber,
                customerName,
                new StayPeriod(startDate, endDate),
                roomSegment,
                paymentMode,
                paymentReference,
                status,
                cancellationReason,
                new Money(totalAmount, currency),
                new Money(amountReceived, currency),
                paymentDeadlineAt,
                version,
                createdAt,
                updatedAt);
    }
}
