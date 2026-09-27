package com.marvel.hospitality.notification.api;

/** springdoc {@code @ExampleObject} bodies for {@code GET /notifications}, kept in one place. */
final class NotificationApiExamples {

    static final String NOTIFICATIONS_RESPONSE = """
            [
              {
                "id": "0b6f3c1e-8a52-4d0e-9c43-2f7d1e5a9b10",
                "eventId": "7d3f5a2b-1c4e-4f6a-8b9d-0e1f2a3b4c5d",
                "reservationId": "P4145478",
                "propertyId": "AMS01",
                "channel": "LOG",
                "template": "RESERVATION_CREATED_PENDING_PAYMENT",
                "renderedText": "Dear Ada Lovelace,\\n\\nthank you for your reservation P4145478: room 201 at property AMS01, from 2027-10-10 to 2027-10-12.\\nPlease transfer EUR 240.00 to the property's bank account shown in your booking confirmation, with the description \\"<your E2E id> P4145478\\", so that it arrives by 2027-10-07 22:00 UTC. Partial transfers add up.\\nIf the full amount has not arrived by then, the reservation is cancelled automatically.",
                "createdAt": "2026-09-26T10:00:01Z"
              },
              {
                "id": "5a1d9e7c-3b2f-4c8a-a6e0-9f8b7c6d5e4f",
                "eventId": "c2e4a6b8-0d1f-4a3c-9e5b-7d9f1b3d5f7a",
                "reservationId": "P4145478",
                "propertyId": "AMS01",
                "channel": "LOG",
                "template": "RESERVATION_CONFIRMED",
                "renderedText": "Dear Ada Lovelace,\\n\\nyour reservation P4145478 is confirmed: room 201 at property AMS01, from 2027-10-10 to 2027-10-12.\\nTotal: EUR 240.00 (payment: BANK_TRANSFER, received so far: EUR 240.00).",
                "createdAt": "2026-10-01T09:15:04Z"
              }
            ]""";

    static final String VALIDATION_FAILED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/VALIDATION_FAILED",
              "title": "Bad Request",
              "status": 400,
              "detail": "Required parameter 'reservationId' is not present.",
              "instance": "/notifications",
              "code": "VALIDATION_FAILED"
            }""";

    static final String UNAUTHENTICATED_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/UNAUTHENTICATED",
              "title": "Unauthorized",
              "status": 401,
              "detail": "Full authentication is required to access this resource.",
              "instance": "/notifications",
              "code": "UNAUTHENTICATED"
            }""";

    static final String FORBIDDEN_EXAMPLE = """
            {
              "type": "https://marvel-hospitality/problems/FORBIDDEN",
              "title": "Forbidden",
              "status": 403,
              "detail": "Access Denied",
              "instance": "/notifications",
              "code": "FORBIDDEN"
            }""";

    private NotificationApiExamples() {
    }
}
