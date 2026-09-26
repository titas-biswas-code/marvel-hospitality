package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.application.GetBankTransactionUseCase;
import com.marvel.hospitality.payment.application.IngestBankTransactionUseCase;
import com.marvel.hospitality.payment.application.IngestResult;
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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * "The bank webhook" and the ledger read (rest-api.md, ADR-0014). The ledger is not property-scoped (a bank transfer
 * arrives before anyone knows which property it belongs to), so only roles are checked, no {@code propertyId}.
 */
@RestController
@RequestMapping("/bank-transactions")
@Tag(name = "Bank transactions", description = "Ingest bank transactions (the bank webhook) and read the ledger.")
class BankTransactionController {

    private final IngestBankTransactionUseCase ingestUseCase;
    private final GetBankTransactionUseCase getUseCase;
    private final JsonMapper jsonMapper;

    BankTransactionController(
            IngestBankTransactionUseCase ingestUseCase, GetBankTransactionUseCase getUseCase, JsonMapper jsonMapper) {
        this.ingestUseCase = ingestUseCase;
        this.getUseCase = getUseCase;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('bank:ingest')")
    @Operation(summary = "Ingest a bank transaction",
            description = "Stores the transaction in the ledger and publishes PaymentReceived on "
                    + "bank-transfer-payment-update (via the outbox). Idempotent on bankTransactionRef: a repeat "
                    + "answers 200 with the existing paymentId and publishes nothing.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = IngestBankTransactionRequest.class),
            examples = @ExampleObject(value = BankTransactionApiExamples.INGEST_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "New transaction stored; event queued for publishing",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestBankTransactionResponse.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.INGEST_RESPONSE))),
            @ApiResponse(responseCode = "200", description = "bankTransactionRef already ingested; existing paymentId, no new event",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestBankTransactionResponse.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.INGEST_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (e.g. amount <= 0, missing field)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.VALIDATION_FAILED_EXAMPLE))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.UNAUTHENTICATED_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN (token lacks bank:ingest)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "422", description = "UNSUPPORTED_CURRENCY (anything but EUR)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.UNSUPPORTED_CURRENCY_EXAMPLE)))})
    ResponseEntity<IngestBankTransactionResponse> ingest(
            @Valid @org.springframework.web.bind.annotation.RequestBody IngestBankTransactionRequest request) {
        IngestResult result = ingestUseCase.ingest(request.toCommand(jsonMapper.writeValueAsString(request)));
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(IngestBankTransactionResponse.from(result));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('bank:read')")
    @Operation(summary = "Get a bank transaction by paymentId")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BankTransactionResponse.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.BANK_TRANSACTION_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (paymentId is not a UUID)",
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
            @ApiResponse(responseCode = "404", description = "BANK_TRANSACTION_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = BankTransactionApiExamples.NOT_FOUND_EXAMPLE)))})
    BankTransactionResponse get(
            @Parameter(in = ParameterIn.PATH, example = "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f") @PathVariable UUID paymentId) {
        return BankTransactionResponse.from(getUseCase.get(paymentId));
    }
}
