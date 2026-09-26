package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.GetReservationUseCase;
import com.marvel.hospitality.reservation.application.ReservationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
            description = "Creates a reservation and, depending on paymentMode, confirms it immediately (CASH) or "
                    + "puts it in PENDING_PAYMENT with a bank-transfer deadline (BANK_TRANSFER). CREDIT_CARD "
                    + "returns 501 in this release (PR-03 adds it).")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = CreateReservationRequest.class),
            examples = {
                    @ExampleObject(name = "Bank transfer", value = ReservationApiExamples.CREATE_BANK_TRANSFER_REQUEST),
                    @ExampleObject(name = "Cash", value = ReservationApiExamples.CREATE_CASH_REQUEST)}))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReservationResponse.class),
                            examples = {
                                    @ExampleObject(name = "Bank transfer", value = ReservationApiExamples.BANK_TRANSFER_RESPONSE),
                                    @ExampleObject(name = "Cash", value = ReservationApiExamples.CASH_RESPONSE)})),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: bad request shape or an invalid stay",
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
            @ApiResponse(responseCode = "409", description = "ROOM_UNAVAILABLE",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.ROOM_UNAVAILABLE_EXAMPLE))),
            @ApiResponse(responseCode = "422", description = "ROOM_SEGMENT_MISMATCH or BANK_TRANSFER_LEAD_TIME_TOO_SHORT",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "ROOM_SEGMENT_MISMATCH", value = ReservationApiExamples.ROOM_SEGMENT_MISMATCH_EXAMPLE),
                                    @ExampleObject(name = "BANK_TRANSFER_LEAD_TIME_TOO_SHORT",
                                            value = ReservationApiExamples.BANK_TRANSFER_LEAD_TIME_TOO_SHORT_EXAMPLE)})),
            @ApiResponse(responseCode = "501", description = "NOT_IMPLEMENTED_YET: paymentMode CREDIT_CARD (removed in PR-03)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = ReservationApiExamples.NOT_IMPLEMENTED_YET_EXAMPLE)))})
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
