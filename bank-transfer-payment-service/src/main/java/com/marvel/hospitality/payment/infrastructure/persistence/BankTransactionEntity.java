package com.marvel.hospitality.payment.infrastructure.persistence;

import com.marvel.hospitality.payment.domain.BankTransaction;
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
 * Read model of a {@code bank_transaction} row. Rows are only ever inserted through
 * {@link BankTransactionJpaRepository#insertIfAbsent} (a native {@code ON CONFLICT DO NOTHING}), so the entity is
 * {@link Immutable}: Hibernate never writes it back.
 */
@Entity
@Immutable
@Table(name = "bank_transaction")
class BankTransactionEntity {

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "bank_transaction_ref", nullable = false, unique = true)
    private String bankTransactionRef;

    @Column(name = "debtor_account_number", nullable = false)
    private String debtorAccountNumber;

    @Column(name = "debtor_name")
    private String debtorName;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    // char(3) in the schema (ISO 4217); Hibernate would otherwise validate against varchar.
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "remittance_information", nullable = false)
    private String remittanceInformation;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false)
    private String raw;

    protected BankTransactionEntity() {
    }

    BankTransaction toDomain() {
        return new BankTransaction(paymentId, bankTransactionRef, debtorAccountNumber, debtorName, amount, currency,
                remittanceInformation, bookedAt, receivedAt);
    }
}
