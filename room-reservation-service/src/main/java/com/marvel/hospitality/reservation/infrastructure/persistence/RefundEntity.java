package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.Refund;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.RefundStatus;
import com.marvel.hospitality.reservation.domain.ReservationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * JPA mapping of {@code refund} (database-schemas.md). Only the outcome columns change after the insert.
 * {@code payment_id} and {@code reservation_id} are plain columns, like {@link ReceivedPaymentEntity}'s.
 */
@Entity
@Table(name = "refund")
class RefundEntity {

    @Id
    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private String reservationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private String propertyId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    // char(3) in the schema (ISO 4217); Hibernate would otherwise validate against varchar.
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefundStatus status;

    @Column(name = "failure_reason")
    private @Nullable String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    protected RefundEntity() {
        // JPA
    }

    static RefundEntity fromDomain(Refund refund) {
        RefundEntity entity = new RefundEntity();
        entity.refundId = refund.refundId();
        entity.paymentId = refund.paymentId();
        entity.reservationId = refund.reservationId().value();
        entity.propertyId = refund.propertyId();
        entity.amount = refund.amount().amount();
        entity.currency = refund.amount().currency();
        entity.reason = refund.reason();
        entity.requestedAt = refund.requestedAt();
        entity.applyOutcome(refund);
        return entity;
    }

    void applyOutcome(Refund refund) {
        status = refund.status();
        failureReason = refund.failureReason();
        completedAt = refund.completedAt();
    }

    Refund toDomain() {
        return Refund.rehydrate(refundId, paymentId, ReservationId.of(reservationId), propertyId,
                new Money(amount, currency), reason, status, failureReason, requestedAt, completedAt);
    }
}
