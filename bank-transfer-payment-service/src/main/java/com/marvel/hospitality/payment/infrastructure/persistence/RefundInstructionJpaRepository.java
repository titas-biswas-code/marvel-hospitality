package com.marvel.hospitality.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefundInstructionJpaRepository extends JpaRepository<RefundInstructionEntity, UUID> {

    /**
     * Inserts one {@code refund_instruction} row in its final state. Native because the entity is {@link
     * org.hibernate.annotations.Immutable} and never goes through JPA's persist/merge lifecycle; there is no
     * {@code ON CONFLICT} because {@link com.marvel.hospitality.payment.application.RefundInbox} already guarantees
     * each {@code refundId} reaches this insert at most once.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO refund_instruction
                (refund_id, payment_id, reservation_id, property_id, creditor_account_number, amount, currency,
                 reason, status, failure_reason, created_at, executed_at)
            VALUES
                (:refundId, :paymentId, :reservationId, :propertyId, :creditorAccountNumber, :amount, :currency,
                 :reason, :status, :failureReason, :createdAt, :executedAt)
            """)
    void insert(
            @Param("refundId") UUID refundId,
            @Param("paymentId") UUID paymentId,
            @Param("reservationId") String reservationId,
            @Param("propertyId") String propertyId,
            @Param("creditorAccountNumber") String creditorAccountNumber,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("reason") String reason,
            @Param("status") String status,
            @Param("failureReason") @Nullable String failureReason,
            @Param("createdAt") Instant createdAt,
            @Param("executedAt") @Nullable Instant executedAt);
}
