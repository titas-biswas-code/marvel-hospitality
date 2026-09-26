package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.ReservationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * JPA mapping of {@code received_payment} (database-schemas.md). Insert-only: a payment's outcome is decided once,
 * when it arrives. {@code reservation_id} is mapped as a plain column rather than an association: the row is read on
 * its own, and for unmatched payments there is no reservation to associate.
 */
@Entity
@Table(name = "received_payment")
class ReceivedPaymentEntity {

    @Id
    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "reservation_id")
    private @Nullable String reservationId;

    @Column(name = "property_id")
    private @Nullable String propertyId;

    @Column(name = "debtor_account_number", nullable = false)
    private String debtorAccountNumber;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    // char(3) in the schema (ISO 4217); Hibernate would otherwise validate against varchar.
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "transaction_description", nullable = false)
    private String transactionDescription;

    @Column(name = "e2e_id")
    private @Nullable String e2eId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private PaymentMatchOutcome outcome;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ReceivedPaymentEntity() {
        // JPA
    }

    static ReceivedPaymentEntity fromDomain(ReceivedPayment payment) {
        ReceivedPaymentEntity entity = new ReceivedPaymentEntity();
        entity.paymentId = payment.paymentId();
        entity.reservationId = payment.reservationId() == null ? null : payment.reservationId().value();
        entity.propertyId = payment.propertyId();
        entity.debtorAccountNumber = payment.debtorAccountNumber();
        entity.amount = payment.amount().amount();
        entity.currency = payment.amount().currency();
        entity.transactionDescription = payment.transactionDescription();
        entity.e2eId = payment.e2eId();
        entity.outcome = payment.outcome();
        entity.receivedAt = payment.receivedAt();
        return entity;
    }

    ReceivedPayment toDomain() {
        return new ReceivedPayment(paymentId, reservationId == null ? null : ReservationId.of(reservationId),
                propertyId, debtorAccountNumber, new Money(amount, currency), transactionDescription, e2eId, outcome,
                receivedAt);
    }
}
