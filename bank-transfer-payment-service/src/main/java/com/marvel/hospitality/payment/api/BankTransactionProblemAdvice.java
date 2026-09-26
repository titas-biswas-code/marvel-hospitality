package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.application.BankTransactionNotFoundException;
import com.marvel.hospitality.payment.domain.UnsupportedCurrencyException;
import com.marvel.hospitality.platform.problem.Problems;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps this service's exceptions to rest-api.md's codes. Ordered ahead of platform/problem-starter's fallback advice
 * ({@link Problems#SERVICE_ADVICE_ORDER}), which already answers bean-validation and malformed-body errors with
 * {@code 400 VALIDATION_FAILED}. No catch-all handler here, so Spring Security's denials still reach the security
 * starter's 401/403 handling.
 */
@RestControllerAdvice
@Order(Problems.SERVICE_ADVICE_ORDER)
class BankTransactionProblemAdvice {

    @ExceptionHandler(UnsupportedCurrencyException.class)
    ProblemDetail handleUnsupportedCurrency(UnsupportedCurrencyException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCodes.UNSUPPORTED_CURRENCY, ex.getMessage(), request);
    }

    @ExceptionHandler(BankTransactionNotFoundException.class)
    ProblemDetail handleNotFound(BankTransactionNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemCodes.BANK_TRANSACTION_NOT_FOUND, ex.getMessage(), request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(status, code, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
