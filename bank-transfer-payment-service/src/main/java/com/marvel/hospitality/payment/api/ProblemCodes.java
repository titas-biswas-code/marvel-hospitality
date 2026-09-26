package com.marvel.hospitality.payment.api;

/**
 * Stable {@code code} extension values this service adds to {@code https://marvel-hospitality/problems/<code>}
 * (rest-api.md, bank-transfer-payment-service section).
 */
final class ProblemCodes {

    static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
    static final String BANK_TRANSACTION_NOT_FOUND = "BANK_TRANSACTION_NOT_FOUND";

    private ProblemCodes() {
    }
}
