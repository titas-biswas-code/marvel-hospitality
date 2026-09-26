package com.marvel.hospitality.payment.application;

import java.util.UUID;

/** 404 BANK_TRANSACTION_NOT_FOUND. */
public class BankTransactionNotFoundException extends RuntimeException {

    public BankTransactionNotFoundException(UUID paymentId) {
        super("Bank transaction " + paymentId + " not found.");
    }
}
