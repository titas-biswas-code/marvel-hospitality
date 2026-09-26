package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.BankTransaction;

/**
 * @param transaction the ledger entry: the new one, or the existing one for a repeated {@code bankTransactionRef}
 * @param created {@code true} when this call stored it (and wrote its {@code PaymentReceived} outbox row)
 */
public record IngestResult(BankTransaction transaction, boolean created) {
}
