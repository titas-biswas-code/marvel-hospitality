package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.GetReservationUseCase;
import com.marvel.hospitality.reservation.application.ReservationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Creates and reads reservations (rest-api.md). Authorization always checks the role first
 * ({@code hasAuthority(...)}) and the property second ({@code @propertyAccess.allowed(#propertyId)}) so a role
 * mismatch answers {@code 403 FORBIDDEN} and a property mismatch answers {@code 403 FORBIDDEN_PROPERTY}
 * (ADR-0012, platform/security-starter's {@code PropertyAccess}).
 */
@RestController
@RequestMapping("/properties/{propertyId}/reservations")
@Tag(name = "Reservations", description = "Create and retrieve reservations.")
class ReservationController {

    private final CreateReservationUseCase createReservationUseCase;
    private final GetReservationUseCase getReservationUseCase;

    ReservationController(CreateReservationUseCase createReservationUseCase, GetReservationUseCase getReservationUseCase) {
        this.createReservationUseCase = createReservationUseCase;
        this.getReservationUseCase = getReservationUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('reservation:write') and @propertyAccess.allowed(#propertyId)")
    @Operation(summary = "Create a reservation",
            description = "Creates a reservation and, depending on paymentMode, confirms it immediately (CASH), "
                    + "puts it in PENDING_PAYMENT with a bank-transfer deadline (BANK_TRANSFER), or checks "
                    + "paymentReference with the credit-card payment service and confirms it (CREDIT_CARD; "
                    + "paymentReference required). A rejected or unknown card payment is 422 and an unreachable "
                    + "payment service is 503 with Retry-After; in both cases nothing is stored.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = CreateReservationRequest.class),
            examples = {
                    @ExampleObject(name = "Bank transfer", value = ReservationApiExamples.CREATE_BANK_TRANSFER_REQUEST),
                    @ExampleObject(name = "Cash", value = ReservationApiExamples.CREATE_CASH_REQUEST),
                    @ExampleObject(name = "Credit card", value = ReservationApiExamples.CREATE_CREDIT_CARD_REQUEST)}))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReservationResponse.class),
                            examples = {
                                    @ExampleObject(name = "Bank transfer", value = ReservationApiExamples.BANK_TRANSFER_RESPONSE),
                                    @ExampleObject(name = "Cash", value = ReservationApiExamples.CASH_RESPONSE),
                                    @ExampleObject(name = "Credit card", value = ReservationApiExamples.CREDIT_CARD_RESPONSE)})),
            @ApiResponse(responseCode = "400",
                    description = "VALIDATION_FAILED: bad request shape, an invalid stay, or CREDIT_CARD without paymentReference",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.VALIDATION_FAILED_EXAMPLE))),
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
            @ApiResponse(responseCode = "404", description = "PROPERTY_NOT_FOUND or ROOM_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "PROPERTY_NOT_FOUND", value = ReservationApiExamples.PROPERTY_NOT_FOUND_EXAMPLE),
                                    @ExampleObject(name = "ROOM_NOT_FOUND", value = ReservationApiExamples.ROOM_NOT_FOUND_EXAMPLE)})),
            @ApiResponse(responseCode = "409",
                    description = "ROOM_UNAVAILABLE, or PAYMENT_REFERENCE_ALREADY_USED (card payment already backs a reservation)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "ROOM_UNAVAILABLE", value = ReservationApiExamples.ROOM_UNAVAILABLE_EXAMPLE),
                                    @ExampleObject(name = "PAYMENT_REFERENCE_ALREADY_USED",
                                            value = ReservationApiExamples.PAYMENT_REFERENCE_ALREADY_USED_EXAMPLE)})),
            @ApiResponse(responseCode = "422",
                    description = "ROOM_SEGMENT_MISMATCH, BANK_TRANSFER_LEAD_TIME_TOO_SHORT or PAYMENT_REJECTED (card payment rejected or unknown)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "ROOM_SEGMENT_MISMATCH", value = ReservationApiExamples.ROOM_SEGMENT_MISMATCH_EXAMPLE),
                                    @ExampleObject(name = "BANK_TRANSFER_LEAD_TIME_TOO_SHORT",
                                            value = ReservationApiExamples.BANK_TRANSFER_LEAD_TIME_TOO_SHORT_EXAMPLE),
                                    @ExampleObject(name = "PAYMENT_REJECTED", value = ReservationApiExamples.PAYMENT_REJECTED_EXAMPLE)})),
            @ApiResponse(responseCode = "503",
                    description = "PAYMENT_SERVICE_UNAVAILABLE: credit-card payment service timed out, failed or circuit open; see Retry-After",
                    headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer", example = "5")),
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.PAYMENT_SERVICE_UNAVAILABLE_EXAMPLE)))})
    ResponseEntity<ReservationResponse> create(
            @Parameter(in = ParameterIn.PATH, example = "AMS01") @PathVariable String propertyId,
            @Valid @org.springframework.web.bind.annotation.RequestBody CreateReservationRequest request) {
        ReservationView view = createReservationUseCase.create(request.toCommand(propertyId));
        ReservationResponse body = ReservationResponse.from(view);
        URI location = UriComponentsBuilder.fromPath("/properties/{propertyId}/reservations/{reservationId}")
                .buildAndExpand(propertyId, body.reservationId())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{reservationId}")
    @PreAuthorize("hasAuthority('reservation:read') and @propertyAccess.allowed(#propertyId)")
    @Operation(summary = "Get a reservation by id",
            description = "404 RESERVATION_NOT_FOUND also when the reservation exists under a different property.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReservationResponse.class),
                            examples = {
                                    @ExampleObject(name = "Bank transfer", value = ReservationApiExamples.BANK_TRANSFER_RESPONSE),
                                    @ExampleObject(name = "Cash", value = ReservationApiExamples.CASH_RESPONSE)})),
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
            @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.RESERVATION_NOT_FOUND_EXAMPLE)))})
    ReservationResponse get(
            @Parameter(in = ParameterIn.PATH, example = "AMS01") @PathVariable String propertyId,
            @Parameter(in = ParameterIn.PATH, example = "P4145478") @PathVariable String reservationId) {
        return ReservationResponse.from(getReservationUseCase.get(propertyId, reservationId));
    }
}
