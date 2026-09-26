package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.BankTransaction;
import java.util.Optional;
import java.util.UUID;

/** Port to the {@code bank_transaction} table. */
public interface BankTransactionLedger {

    /**
     * Stores {@code transaction} unless one with the same {@code bankTransactionRef} already exists, atomically
     * (a unique constraint, not a check-then-insert), and joins the caller's transaction.
     *
     * @param raw the request exactly as received, as JSON, kept for auditing
     * @return {@code true} if it was stored, {@code false} if the reference was already in the ledger
     */
    boolean addIfAbsent(BankTransaction transaction, String raw);

    Optional<BankTransaction> findByBankTransactionRef(String bankTransactionRef);

    Optional<BankTransaction> findByPaymentId(UUID paymentId);
}
