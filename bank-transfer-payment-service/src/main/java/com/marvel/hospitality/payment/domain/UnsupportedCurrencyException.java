package com.marvel.hospitality.payment.domain;

/** A bank transaction in a currency other than {@link BankTransaction#SUPPORTED_CURRENCY} (422 UNSUPPORTED_CURRENCY). */
public class UnsupportedCurrencyException extends RuntimeException {

    private final String currency;

    public UnsupportedCurrencyException(String currency) {
        super("Currency " + currency + " is not supported; only " + BankTransaction.SUPPORTED_CURRENCY + " is.");
        this.currency = currency;
    }

    public String currency() {
        return currency;
    }
}
