package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.platform.problem.InvalidField;
import com.marvel.hospitality.platform.problem.Problems;
import com.marvel.hospitality.reservation.application.PaymentReferenceAlreadyUsedException;
import com.marvel.hospitality.reservation.application.PaymentRejectedException;
import com.marvel.hospitality.reservation.application.PaymentServiceUnavailableException;
import com.marvel.hospitality.reservation.application.PropertyNotFoundException;
import com.marvel.hospitality.reservation.application.ReservationNotFoundException;
import com.marvel.hospitality.reservation.application.RoomNotFoundException;
import com.marvel.hospitality.reservation.application.RoomUnavailableException;
import com.marvel.hospitality.reservation.domain.BankTransferLeadTimeTooShortException;
import com.marvel.hospitality.reservation.domain.InvalidStayException;
import com.marvel.hospitality.reservation.domain.RoomSegmentMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps this service's application/domain exceptions to the {@link ProblemDetail} codes rest-api.md promises.
 *
 * <p>Registered at {@link Problems#SERVICE_ADVICE_ORDER}, ahead of platform/problem-starter's
 * {@code FallbackProblemAdvice} (registered at {@code Ordered.LOWEST_PRECEDENCE}): Spring MVC's
 * {@code ExceptionHandlerExceptionResolver} sorts every {@code @ControllerAdvice} by {@code @Order} and stops at the
 * FIRST one whose {@code @ExceptionHandler} matches the thrown exception. Without this explicit order (or with a
 * wrong one), this advice could sort after the fallback's catch-all {@code Exception} handler and never run.
 *
 * <p>Deliberately does NOT declare a catch-all {@code Exception}/{@code RuntimeException} handler: Spring Security's
 * method-security denials ({@code AccessDeniedException}, {@code AuthenticationException}) are plain
 * {@code RuntimeException}s as far as MVC exception resolution is concerned, and must reach
 * {@code ExceptionTranslationFilter} untouched so {@code SecurityProblemHandler} (platform/security-starter) can
 * answer 401/403. A broad handler here would swallow them into a wrong 500 before they get that far.
 *
 * <p>Bean Validation failures ({@code MethodArgumentNotValidException}) and malformed JSON / unknown enum values
 * ({@code HttpMessageNotReadableException}) are already turned into {@code 400 VALIDATION_FAILED} by
 * {@code FallbackProblemAdvice}, so this class does not repeat that handling — only {@link InvalidStayException},
 * which is a domain rule raised deep inside the use case rather than a request-binding failure, needs its own
 * {@code 400} mapping here.
 */
@RestControllerAdvice
@Order(Problems.SERVICE_ADVICE_ORDER)
class ReservationProblemAdvice {

    private final Duration paymentServiceRetryAfter;

    ReservationProblemAdvice(
            @Value("${reservation.payment-service-unavailable.retry-after}") Duration paymentServiceRetryAfter) {
        this.paymentServiceRetryAfter = paymentServiceRetryAfter;
    }

    @ExceptionHandler(InvalidStayException.class)
    ProblemDetail handleInvalidStay(InvalidStayException ex, HttpServletRequest request) {
        ProblemDetail problem = Problems.validationFailed(
                "Validation failed.", List.of(new InvalidField(ex.field(), ex.getMessage())));
        problem.setInstance(instance(request));
        return problem;
    }

    @ExceptionHandler(PropertyNotFoundException.class)
    ProblemDetail handlePropertyNotFound(PropertyNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemCodes.PROPERTY_NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(RoomNotFoundException.class)
    ProblemDetail handleRoomNotFound(RoomNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemCodes.ROOM_NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    ProblemDetail handleReservationNotFound(ReservationNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemCodes.RESERVATION_NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(RoomUnavailableException.class)
    ProblemDetail handleRoomUnavailable(RoomUnavailableException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemCodes.ROOM_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(RoomSegmentMismatchException.class)
    ProblemDetail handleRoomSegmentMismatch(RoomSegmentMismatchException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCodes.ROOM_SEGMENT_MISMATCH, ex.getMessage(), request);
    }

    @ExceptionHandler(BankTransferLeadTimeTooShortException.class)
    ProblemDetail handleBankTransferLeadTimeTooShort(BankTransferLeadTimeTooShortException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT, ProblemCodes.BANK_TRANSFER_LEAD_TIME_TOO_SHORT, ex.getMessage(), request);
    }

    @ExceptionHandler(PaymentReferenceAlreadyUsedException.class)
    ProblemDetail handlePaymentReferenceAlreadyUsed(PaymentReferenceAlreadyUsedException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemCodes.PAYMENT_REFERENCE_ALREADY_USED, ex.getMessage(), request);
    }

    @ExceptionHandler(PaymentRejectedException.class)
    ProblemDetail handlePaymentRejected(PaymentRejectedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCodes.PAYMENT_REJECTED, ex.getMessage(), request);
    }

    /** {@code Retry-After} in seconds (rest-api.md): nothing was persisted, and the same request is safe to repeat. */
    @ExceptionHandler(PaymentServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> handlePaymentServiceUnavailable(
            PaymentServiceUnavailableException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE, ProblemCodes.PAYMENT_SERVICE_UNAVAILABLE, ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(paymentServiceRetryAfter.toSeconds()))
                .body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(status, code, detail);
        problem.setInstance(instance(request));
        return problem;
    }

    private static URI instance(HttpServletRequest request) {
        return URI.create(request.getRequestURI());
    }
}
