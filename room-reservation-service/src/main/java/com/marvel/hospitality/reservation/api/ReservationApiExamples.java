package com.marvel.hospitality.reservation.api;

/**
 * springdoc {@code @ExampleObject} bodies, copied verbatim from docs/contracts/rest-api.md so Swagger UI shows
 * exactly what the contract promises. Kept as named constants rather than inline strings so a contract change is
 * one place to update.
 */
final class ReservationApiExamples {

    static final String CREATE_BANK_TRANSFER_REQUEST = """
            {
              "customerName": "Ada Lovelace",
              "roomNumber": "101",
              "startDate": "2026-10-10",
              "endDate": "2026-10-12",
              "roomSegment": "MEDIUM",
              "paymentMode": "BANK_TRANSFER",
              "paymentReference": null
            }""";

    static final String CREATE_CASH_REQUEST = """
            {
              "customerName": "Ada Lovelace",
              "roomNumber": "101",
              "startDate": "2026-10-10",
              "endDate": "2026-10-12",
              "roomSegment": "MEDIUM",
              "paymentMode": "CASH",
              "paymentReference": null
            }""";

    static final String CREATE_CREDIT_CARD_REQUEST = """
            {
              "customerName": "Ada Lovelace",
              "roomNumber": "101",
              "startDate": "2026-10-10",
              "endDate": "2026-10-12",
              "roomSegment": "MEDIUM",
              "paymentMode": "CREDIT_CARD",
              "paymentReference": "OK-123"
            }""";

    static final String BANK_TRANSFER_RESPONSE = """
            {
              "reservationId": "P4145478",
              "propertyId": "AMS01",
              "status": "PENDING_PAYMENT",
              "customerName": "Ada Lovelace",
              "roomNumber": "101",
              "roomSegment": "MEDIUM",
              "startDate": "2026-10-10",
              "endDate": "2026-10-12",
              "nights": 2,
              "paymentMode": "BANK_TRANSFER",
              "paymentReference": null,
              "totalAmount": 240.00,
              "amountReceived": 0.00,
              "currency": "EUR",
              "paymentDeadlineAt": "2026-10-07T22:00:00Z",
              "bankTransferInstructions": "Transfer 240.00 EUR to NL00MARV0000000001 with description '<your E2E id> P4145478'",
              "createdAt": "2026-09-26T10:00:00Z",
              "updatedAt": "2026-09-26T10:00:00Z"
            }""";

    static final String CASH_RESPONSE = """
            {
              "reservationId": "P4145478",
              "propertyId": "AMS01",
              "status": "CONFIRMED",
              "customerName": "Ada Lovelace",
              "roomNumber": "101",
              "roomSegment": "MEDIUM",
              "startDate": "2026-10-10",
              "endDate": "2026-10-12",
              "nights": 2,
              "paymentMode": "CASH",
              "paymentReference": null,
              "totalAmount": 240.00,
              "amountReceived": 0.00,
              "currency": "EUR",
              "paymentDeadlineAt": null,
              "bankTransferInstructions": null,
              "createdAt": "2026-09-26T10:00:00Z",
              "updatedAt": "2026-09-26T10:00:00Z"
            }""";

    static final String CREDIT_CARD_RESPONSE = """
            {
              "reservationId": "P4145478",
              "propertyId": "AMS01",
              "status": "CONFIRMED",
              "customerName": "Ada Lovelace",
              "roomNumber": "101",
              "roomSegment": "MEDIUM",
              "startDate": "2026-10-10",
              "endDate": "2026-10-12",
              "nights": 2,
              "paymentMode": "CREDIT_CARD",
              "paymentReference": "OK-123",
              "totalAmount": 240.00,
              "amountReceived": 0.00,
              "currency": "EUR",
              "paymentDeadlineAt": null,
              "bankTransferInstructions": null,
              "createdAt": "2026-09-26T10:00:00Z",
              "updatedAt": "2026-09-26T10:00:00Z"
            }""";

    static final String VALIDATION_FAILED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/VALIDATION_FAILED",
              "title": "Bad Request",
              "status": 400,
              "detail": "Validation failed.",
              "instance": "/properties/AMS01/reservations",
              "code": "VALIDATION_FAILED",
              "errors": [ { "field": "customerName", "message": "must not be blank" } ]
            }""";

    static final String PROPERTY_NOT_FOUND_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/PROPERTY_NOT_FOUND",
              "title": "Not Found",
              "status": 404,
              "detail": "Property AMS01 does not exist.",
              "instance": "/properties/AMS01/reservations",
              "code": "PROPERTY_NOT_FOUND"
            }""";

    static final String ROOM_NOT_FOUND_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/ROOM_NOT_FOUND",
              "title": "Not Found",
              "status": 404,
              "detail": "Room 999 does not exist in property AMS01.",
              "instance": "/properties/AMS01/reservations",
              "code": "ROOM_NOT_FOUND"
            }""";

    static final String RESERVATION_NOT_FOUND_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/RESERVATION_NOT_FOUND",
              "title": "Not Found",
              "status": 404,
              "detail": "Reservation P4145478 does not exist in property AMS01.",
              "instance": "/properties/AMS01/reservations/P4145478",
              "code": "RESERVATION_NOT_FOUND"
            }""";

    static final String ROOM_UNAVAILABLE_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/ROOM_UNAVAILABLE",
              "title": "Conflict",
              "status": 409,
              "detail": "Room 101 in property AMS01 is already booked for some of these nights.",
              "instance": "/properties/AMS01/reservations",
              "code": "ROOM_UNAVAILABLE"
            }""";

    static final String PAYMENT_REFERENCE_ALREADY_USED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/PAYMENT_REFERENCE_ALREADY_USED",
              "title": "Conflict",
              "status": 409,
              "detail": "CREDIT_CARD payment OK-123 is already used by another reservation.",
              "instance": "/properties/AMS01/reservations",
              "code": "PAYMENT_REFERENCE_ALREADY_USED"
            }""";

    static final String ROOM_SEGMENT_MISMATCH_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/ROOM_SEGMENT_MISMATCH",
              "title": "Unprocessable Content",
              "status": 422,
              "detail": "Requested segment LARGE does not match room segment MEDIUM",
              "instance": "/properties/AMS01/reservations",
              "code": "ROOM_SEGMENT_MISMATCH"
            }""";

    static final String BANK_TRANSFER_LEAD_TIME_TOO_SHORT_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/BANK_TRANSFER_LEAD_TIME_TOO_SHORT",
              "title": "Unprocessable Content",
              "status": 422,
              "detail": "Payment deadline 2026-09-26T22:00:00Z is not far enough in the future for a bank transfer",
              "instance": "/properties/AMS01/reservations",
              "code": "BANK_TRANSFER_LEAD_TIME_TOO_SHORT"
            }""";

    static final String PAYMENT_REJECTED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/PAYMENT_REJECTED",
              "title": "Unprocessable Content",
              "status": 422,
              "detail": "Payment REJ-1 is REJECTED.",
              "instance": "/properties/AMS01/reservations",
              "code": "PAYMENT_REJECTED"
            }""";

    static final String PAYMENT_SERVICE_UNAVAILABLE_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/PAYMENT_SERVICE_UNAVAILABLE",
              "title": "Service Unavailable",
              "status": 503,
              "detail": "The credit-card payment service is unavailable: no usable answer after retries.",
              "instance": "/properties/AMS01/reservations",
              "code": "PAYMENT_SERVICE_UNAVAILABLE"
            }""";

    static final String FORBIDDEN_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/FORBIDDEN",
              "title": "Forbidden",
              "status": 403,
              "detail": "The token lacks the role required for this operation.",
              "instance": "/properties/AMS01/reservations",
              "code": "FORBIDDEN"
            }""";

    static final String FORBIDDEN_PROPERTY_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/FORBIDDEN_PROPERTY",
              "title": "Forbidden",
              "status": 403,
              "detail": "Not entitled to act on property AMS01.",
              "instance": "/properties/AMS01/reservations",
              "code": "FORBIDDEN_PROPERTY"
            }""";

    static final String UNAUTHENTICATED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/UNAUTHENTICATED",
              "title": "Unauthorized",
              "status": 401,
              "detail": "A bearer token is required.",
              "instance": "/properties/AMS01/reservations",
              "code": "UNAUTHENTICATED"
            }""";

    static final String REFERENCE_DATA_RESPONSE = """
            {
              "reservationStatuses": ["PENDING_PAYMENT","CONFIRMED","CANCELLED"],
              "paymentModes": ["CASH","BANK_TRANSFER","CREDIT_CARD"],
              "roomSegments": ["SMALL","MEDIUM","LARGE","EXTRA_LARGE"],
              "paymentMatchOutcomes": ["MATCHED_PARTIAL","MATCHED_FULL","OVERPAID","UNMATCHED_FORMAT","UNMATCHED_UNKNOWN_RESERVATION","UNMATCHED_NOT_PENDING"],
              "refundReasons": ["OVERPAYMENT","RESERVATION_CANCELLED"],
              "cancellationReasons": ["PAYMENT_DEADLINE_MISSED"]
            }""";

    private ReservationApiExamples() {
    }
}
