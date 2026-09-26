package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.application.ReceivedPaymentQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation views over payments ADR-0009's automatic matching could not apply (rest-api.md).
 *
 * <p>{@code /properties/{propertyId}/unmatched-payments} lists {@code UNMATCHED_NOT_PENDING} payments: they
 * arrived for a reservation that was no longer awaiting payment (already cancelled or already confirmed), so
 * a refund was requested automatically; this view just lets staff follow it up. It carries the same role +
 * property check as every other per-property read.
 *
 * <p>{@code /unmatched-payments} lists {@code UNMATCHED_FORMAT} and {@code UNMATCHED_UNKNOWN_RESERVATION}
 * payments: the bank topic carries no propertyId, so these rows belong to no property and cannot be
 * property-checked. They are deliberately not refunded automatically — a typo could still be reconciled by a
 * human (ADR-0009 §6) — so this endpoint is guarded by {@code bank:read}, the same role that already grants
 * property-less bank data ({@code GET /bank-transactions/{paymentId}} on the payment service).
 */
@RestController
@Tag(name = "Unmatched payments", description = "Reconciliation views over payments that were not applied automatically.")
class UnmatchedPaymentController {

    private final ReceivedPaymentQueries queries;

    UnmatchedPaymentController(ReceivedPaymentQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/properties/{propertyId}/unmatched-payments")
    @PreAuthorize("hasAuthority('reservation:read') and @propertyAccess.allowed(#propertyId)")
    @Operation(summary = "List a property's UNMATCHED_NOT_PENDING payments",
            description = "Payments that arrived for one of this property's reservations after it stopped "
                    + "awaiting payment (cancelled, or already confirmed). Refunded automatically (ADR-0009); "
                    + "this view is for staff follow-up.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ReceivedPaymentResponse.class)),
                            examples = @ExampleObject(value = ReservationApiExamples.NOT_PENDING_PAYMENTS_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.UNAUTHENTICATED_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN (missing role) or FORBIDDEN_PROPERTY (property not in token)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "FORBIDDEN", value = ReservationApiExamples.FORBIDDEN_EXAMPLE),
                                    @ExampleObject(name = "FORBIDDEN_PROPERTY", value = ReservationApiExamples.FORBIDDEN_PROPERTY_EXAMPLE)})),
            @ApiResponse(responseCode = "404", description = "PROPERTY_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.PROPERTY_NOT_FOUND_UNMATCHED_EXAMPLE)))})
    List<ReceivedPaymentResponse> notPendingOfProperty(
            @Parameter(in = ParameterIn.PATH, example = "AMS01") @PathVariable String propertyId) {
        return queries.notPendingOfProperty(propertyId).stream()
                .map(ReceivedPaymentResponse::from)
                .toList();
    }

    @GetMapping("/unmatched-payments")
    @PreAuthorize("hasAuthority('bank:read')")
    @Operation(summary = "List payments with no reservation match at all",
            description = "UNMATCHED_FORMAT (unreadable description) and UNMATCHED_UNKNOWN_RESERVATION payments. "
                    + "These have no property (the bank topic carries none), so this endpoint is guarded by "
                    + "bank:read rather than a property check, and is never refunded automatically: a human may "
                    + "still reconcile a typo (ADR-0009).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ReceivedPaymentResponse.class)),
                            examples = @ExampleObject(value = ReservationApiExamples.PAYMENTS_WITHOUT_RESERVATION_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.UNAUTHENTICATED_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing bank:read",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.FORBIDDEN_EXAMPLE)))})
    List<ReceivedPaymentResponse> withoutReservation() {
        return queries.withoutReservation().stream()
                .map(ReceivedPaymentResponse::from)
                .toList();
    }
}
