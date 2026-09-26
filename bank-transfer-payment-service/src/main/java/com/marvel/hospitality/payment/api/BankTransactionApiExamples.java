package com.marvel.hospitality.payment.api;

/** springdoc {@code @ExampleObject} bodies matching docs/contracts/rest-api.md, kept in one place. */
final class BankTransactionApiExamples {

    static final String INGEST_REQUEST = """
            {
              "bankTransactionRef": "BANK-TX-000123",
              "debtorAccountNumber": "NL91ABNA0417164300",
              "debtorName": "A. Lovelace",
              "amount": 120.00,
              "currency": "EUR",
              "remittanceInformation": "1401541457 P4145478",
              "bookedAt": "2026-10-01T09:15:00Z"
            }""";

    static final String INGEST_RESPONSE = """
            {
              "paymentId": "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
              "bankTransactionRef": "BANK-TX-000123",
              "status": "PUBLISHED"
            }""";

    static final String BANK_TRANSACTION_RESPONSE = """
            {
              "paymentId": "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
              "bankTransactionRef": "BANK-TX-000123",
              "debtorAccountNumber": "NL91ABNA0417164300",
              "debtorName": "A. Lovelace",
              "amount": 120.00,
              "currency": "EUR",
              "remittanceInformation": "1401541457 P4145478",
              "bookedAt": "2026-10-01T09:15:00Z",
              "receivedAt": "2026-10-01T09:15:02Z"
            }""";

    static final String VALIDATION_FAILED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/VALIDATION_FAILED",
              "title": "Bad Request",
              "status": 400,
              "detail": "Validation failed.",
              "instance": "/bank-transactions",
              "code": "VALIDATION_FAILED",
              "errors": [{"field": "amount", "message": "must be greater than 0"}]
            }""";

    static final String UNSUPPORTED_CURRENCY_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/UNSUPPORTED_CURRENCY",
              "title": "Unprocessable Content",
              "status": 422,
              "detail": "Currency USD is not supported; only EUR is.",
              "instance": "/bank-transactions",
              "code": "UNSUPPORTED_CURRENCY"
            }""";

    static final String NOT_FOUND_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/BANK_TRANSACTION_NOT_FOUND",
              "title": "Not Found",
              "status": 404,
              "detail": "Bank transaction 5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f not found.",
              "instance": "/bank-transactions/5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
              "code": "BANK_TRANSACTION_NOT_FOUND"
            }""";

    static final String UNAUTHENTICATED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/UNAUTHENTICATED",
              "title": "Unauthorized",
              "status": 401,
              "detail": "Full authentication is required to access this resource.",
              "instance": "/bank-transactions",
              "code": "UNAUTHENTICATED"
            }""";

    static final String FORBIDDEN_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/FORBIDDEN",
              "title": "Forbidden",
              "status": 403,
              "detail": "Access Denied",
              "instance": "/bank-transactions",
              "code": "FORBIDDEN"
            }""";

    private BankTransactionApiExamples() {
    }
}
