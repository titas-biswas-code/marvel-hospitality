package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.application.GetRefundUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the refund instructions this service creates for {@code refund-requested} (rest-api.md, ADR-0014).
 * Not property-scoped, like {@code GET /bank-transactions/{paymentId}}: only the {@code bank:read} role is checked.
 */
@RestController
@RequestMapping("/refunds")
@Tag(name = "Refunds", description = "Read the refund instructions created for refund-requested (ADR-0014).")
class RefundController {

    private final GetRefundUseCase getUseCase;

    RefundController(GetRefundUseCase getUseCase) {
        this.getUseCase = getUseCase;
    }

    @GetMapping("/{refundId}")
    @PreAuthorize("hasAuthority('bank:read')")
    @Operation(summary = "Get a refund instruction by refundId")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RefundResponse.class),
                            examples = @ExampleObject(value = RefundApiExamples.REFUND_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (refundId is not a UUID)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.UNAUTHENTICATED_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN (token lacks bank:read)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "REFUND_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = RefundApiExamples.NOT_FOUND_EXAMPLE)))})
    RefundResponse get(
            @Parameter(in = ParameterIn.PATH, example = "d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59") @PathVariable UUID refundId) {
        return RefundResponse.from(getUseCase.get(refundId));
    }
}
