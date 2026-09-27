package com.marvel.hospitality.payment.api;

/** springdoc {@code @ExampleObject} bodies matching docs/contracts/rest-api.md, kept in one place. */
final class RefundApiExamples {

    static final String REFUND_RESPONSE = """
            {
              "refundId": "d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59",
              "paymentId": "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
              "amount": 30.00,
              "currency": "EUR",
              "reason": "OVERPAYMENT",
              "status": "EXECUTED",
              "failureReason": null,
              "createdAt": "2026-10-02T11:40:11Z",
              "executedAt": "2026-10-02T11:40:11Z"
            }""";

    static final String NOT_FOUND_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/REFUND_NOT_FOUND",
              "title": "Not Found",
              "status": 404,
              "detail": "Refund d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59 not found.",
              "instance": "/refunds/d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59",
              "code": "REFUND_NOT_FOUND"
            }""";

    private RefundApiExamples() {
    }
}
