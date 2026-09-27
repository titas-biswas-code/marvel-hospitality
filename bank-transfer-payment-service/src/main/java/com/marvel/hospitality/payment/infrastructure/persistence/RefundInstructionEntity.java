package com.marvel.hospitality.payment.infrastructure.persistence;

import com.marvel.hospitality.payment.domain.RefundInstruction;
import com.marvel.hospitality.payment.domain.RefundInstructionStatus;
import com.marvel.hospitality.payment.domain.RefundReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read model of a {@code refund_instruction} row. A refund is only ever written once, in its final state, through
 * {@link RefundInstructionJpaRepository#insert} (a plain native insert: the {@code processed_message} inbox already
 * guarantees a {@code refundId} is written at most once), so the entity is {@link Immutable}: Hibernate never writes
 * it back.
 */
@Entity
@Immutable
@Table(name = "refund_instruction")
class RefundInstructionEntity {

    @Id
    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "reservation_id", nullable = false)
    private String reservationId;

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    @Column(name = "creditor_account_number", nullable = false)
    private String creditorAccountNumber;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    // char(3) in the schema (ISO 4217); Hibernate would otherwise validate against varchar.
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    protected RefundInstructionEntity() {
    }

    RefundInstruction toDomain() {
        return new RefundInstruction(refundId, paymentId, reservationId, propertyId, creditorAccountNumber, amount,
                currency, RefundReason.valueOf(reason), RefundInstructionStatus.valueOf(status), failureReason,
                createdAt, executedAt);
    }
}
