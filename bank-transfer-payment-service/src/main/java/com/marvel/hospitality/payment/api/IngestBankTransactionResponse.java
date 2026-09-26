package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.application.IngestResult;

/**
 * @param status always {@code PUBLISHED} (rest-api.md): the event is committed to the outbox together with the ledger
 *        row, so its publication no longer depends on this request (Debezium delivers it, ADR-0007)
 */
public record IngestBankTransactionResponse(String paymentId, String bankTransactionRef, String status) {

    static final String PUBLISHED = "PUBLISHED";

    static IngestBankTransactionResponse from(IngestResult result) {
        return new IngestBankTransactionResponse(result.transaction().paymentId().toString(),
                result.transaction().bankTransactionRef(), PUBLISHED);
    }
}
