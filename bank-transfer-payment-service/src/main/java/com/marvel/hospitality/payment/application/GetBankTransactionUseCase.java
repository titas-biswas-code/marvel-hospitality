package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.BankTransaction;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetBankTransactionUseCase {

    private final BankTransactionLedger ledger;

    public GetBankTransactionUseCase(BankTransactionLedger ledger) {
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public BankTransaction get(UUID paymentId) {
        return ledger.findByPaymentId(paymentId).orElseThrow(() -> new BankTransactionNotFoundException(paymentId));
    }
}
