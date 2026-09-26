package com.marvel.hospitality.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BankTransactionJpaRepository extends JpaRepository<BankTransactionEntity, UUID> {

    /**
     * Inserts the row unless its {@code bank_transaction_ref} is already taken, and returns the number of rows
     * inserted (1 or 0). Native because JPQL has no {@code ON CONFLICT}. The {@code paymentId} is minted by the
     * caller, so the count is all we need back (no {@code RETURNING}). A concurrent insert of the same reference
     * makes this statement wait for the other transaction and then insert nothing.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO bank_transaction
                (payment_id, bank_transaction_ref, debtor_account_number, debtor_name, amount, currency,
                 remittance_information, booked_at, received_at, raw)
            VALUES
                (:paymentId, :bankTransactionRef, :debtorAccountNumber, :debtorName, :amount, :currency,
                 :remittanceInformation, :bookedAt, :receivedAt, CAST(:raw AS jsonb))
            ON CONFLICT (bank_transaction_ref) DO NOTHING
            """)
    int insertIfAbsent(
            @Param("paymentId") UUID paymentId,
            @Param("bankTransactionRef") String bankTransactionRef,
            @Param("debtorAccountNumber") String debtorAccountNumber,
            @Param("debtorName") @Nullable String debtorName,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("remittanceInformation") String remittanceInformation,
            @Param("bookedAt") Instant bookedAt,
            @Param("receivedAt") Instant receivedAt,
            @Param("raw") String raw);

    Optional<BankTransactionEntity> findByBankTransactionRef(String bankTransactionRef);
}
