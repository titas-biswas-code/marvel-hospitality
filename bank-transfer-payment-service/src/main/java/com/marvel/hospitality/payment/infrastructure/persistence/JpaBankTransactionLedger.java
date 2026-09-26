package com.marvel.hospitality.payment.infrastructure.persistence;

import com.marvel.hospitality.payment.application.BankTransactionLedger;
import com.marvel.hospitality.payment.domain.BankTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class JpaBankTransactionLedger implements BankTransactionLedger {

    private final BankTransactionJpaRepository repository;

    JpaBankTransactionLedger(BankTransactionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean addIfAbsent(BankTransaction transaction, String raw) {
        return repository.insertIfAbsent(
                transaction.paymentId(), transaction.bankTransactionRef(), transaction.debtorAccountNumber(),
                transaction.debtorName(), transaction.amount(), transaction.currency(),
                transaction.remittanceInformation(), transaction.bookedAt(), transaction.receivedAt(), raw) == 1;
    }

    @Override
    public Optional<BankTransaction> findByBankTransactionRef(String bankTransactionRef) {
        return repository.findByBankTransactionRef(bankTransactionRef).map(BankTransactionEntity::toDomain);
    }

    @Override
    public Optional<BankTransaction> findByPaymentId(UUID paymentId) {
        return repository.findById(paymentId).map(BankTransactionEntity::toDomain);
    }
}
