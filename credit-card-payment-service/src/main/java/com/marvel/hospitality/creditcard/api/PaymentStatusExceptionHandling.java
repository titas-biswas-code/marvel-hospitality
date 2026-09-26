package com.marvel.hospitality.creditcard.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The two ways {@code POST /payment-status} answers {@code 400} (src/main/resources/openapi/credit-card-payment-api.yaml):
 * a missing/blank {@code paymentReference} ({@link MethodArgumentNotValidException}, from {@code @NotBlank})
 * or a body that is not readable JSON at all ({@link HttpMessageNotReadableException}, including a missing
 * body). Kept as the spec's own {@link ErrorResponse} shape, not {@code ProblemDetail} — this service's
 * contract is the provided OpenAPI spec, not marvel-hospitality's own error convention.
 */
@RestControllerAdvice
class PaymentStatusExceptionHandling {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusExceptionHandling.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse onValidationFailure(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        log.atInfo().addKeyValue("outcome", "validation-failed").log("payment-status");
        return new ErrorResponse(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse onUnreadableBody(HttpMessageNotReadableException ex) {
        log.atInfo().addKeyValue("outcome", "malformed-body").log("payment-status");
        return new ErrorResponse("Request body is missing or malformed");
    }
}
