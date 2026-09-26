package com.marvel.hospitality.platform.problem;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ClassUtils;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * The last {@code @RestControllerAdvice} consulted for any service that has not already handled an exception
 * itself: every response it produces is a {@link ProblemDetail} with {@code type} and the {@code code} extension
 * property set (docs/contracts/rest-api.md), never a bare Spring Boot error body. Registered at
 * {@code Ordered.LOWEST_PRECEDENCE} so a service's own, more specific advice always runs first — see
 * {@link Problems#SERVICE_ADVICE_ORDER}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} for the long tail of framework exceptions (unsupported method,
 * unreadable body, no handler found, ...) it already turns into {@link ProblemDetail}s; {@link #handleExceptionInternal}
 * is overridden once to stamp {@code type}/{@code code} onto whichever of those bodies does not already carry one,
 * rather than re-implementing each of the dozen {@code handleXxx} hooks. {@link #handleMethodArgumentNotValid},
 * {@link #handleHandlerMethodValidationException} and {@link #handleHttpMessageNotReadable} are overridden
 * individually because their {@code errors} lists need this codebase's shape, not Spring's default one.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class FallbackProblemAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FallbackProblemAdvice.class);

    private static final @Nullable Class<? extends RuntimeException> ACCESS_DENIED_EXCEPTION_TYPE =
            resolve("org.springframework.security.access.AccessDeniedException");

    private static final @Nullable Class<? extends RuntimeException> AUTHENTICATION_EXCEPTION_TYPE =
            resolve("org.springframework.security.core.AuthenticationException");

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(new InvalidField(error.getField(), message(error)));
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new InvalidField(error.getObjectName(), message(error)));
        }
        ProblemDetail problem = Problems.validationFailed("Validation failed.", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String field = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(new InvalidField(field != null ? field : "parameter", message(error)));
            }
        }
        ProblemDetail problem = Problems.validationFailed("Validation failed.", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = describeMalformedJson(ex.getCause())
                .map(field -> Problems.validationFailed("Validation failed.", List.of(field)))
                .orElseGet(() -> Problems.validationFailed("Malformed JSON request body", List.of()));
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Logs the constraint name and answers {@code 500 INTERNAL_ERROR}, never a blanket {@code 409} (ADR-0005): a
     * service must map every constraint it can violate to a precise status/code by name before an exception ever
     * reaches this advice, so arriving here at all means either a new, unmapped constraint or a bug in the
     * service's own mapping — not something a generic HTTP status can express correctly.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        String constraint = ConstraintNames.of(ex).orElse("<unknown>");
        log.warn("Unmapped DB constraint violation: {}", constraint, ex);
        ProblemDetail problem = Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR, Problems.INTERNAL_ERROR, "An unexpected error occurred.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * Catch-all for anything not already handled above or by a service's own advice.
     *
     * <p>Spring Security's method-security denials and authentication failures arrive here too, because they are
     * plain {@code RuntimeException}s as far as MVC's exception resolution is concerned. They must never be
     * swallowed into a {@code 500}: rethrowing lets them propagate out of dispatch and back up through
     * {@code ExceptionTranslationFilter}, which is what actually answers 401/403 (see security-starter's
     * {@code SecurityProblemHandler}). Detected by class name behind {@link ClassUtils#isPresent}, since
     * {@code spring-security-core} is only a {@code compileOnly} dependency here.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        if (isSecurityException(ex)) {
            throw ex;
        }
        log.error("Unexpected error", ex);
        ProblemDetail problem = Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR, Problems.INTERNAL_ERROR, "An unexpected error occurred.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * Single hook every {@code handleXxx} method in the parent class funnels through: stamps {@code type} and the
     * {@code code} extension property onto any {@link ProblemDetail} body that does not already carry a
     * {@code code} (the parent builds one itself, with a generic {@code about:blank} type, for exceptions this
     * class does not override — e.g. unsupported method, no handler found, unsupported media type).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ensureTypeAndCode(problem, statusCode);
        }
        return response;
    }

    private static void ensureTypeAndCode(ProblemDetail problem, HttpStatusCode statusCode) {
        if (problem.getProperties() != null && problem.getProperties().containsKey("code")) {
            return;
        }
        String code = statusCode.value() == HttpStatus.BAD_REQUEST.value()
                ? Problems.VALIDATION_FAILED
                : HttpStatus.valueOf(statusCode.value()).name();
        problem.setType(URI.create(Problems.TYPE_PREFIX + code));
        problem.setProperty("code", code);
    }

    private static boolean isSecurityException(Exception ex) {
        return (ACCESS_DENIED_EXCEPTION_TYPE != null && ACCESS_DENIED_EXCEPTION_TYPE.isInstance(ex))
                || (AUTHENTICATION_EXCEPTION_TYPE != null && AUTHENTICATION_EXCEPTION_TYPE.isInstance(ex));
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends RuntimeException> resolve(String className) {
        ClassLoader classLoader = FallbackProblemAdvice.class.getClassLoader();
        if (!ClassUtils.isPresent(className, classLoader)) {
            return null;
        }
        try {
            return (Class<? extends RuntimeException>) ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static String message(ObjectError error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "invalid value";
    }

    private static String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "invalid value";
    }

    /**
     * Jackson 3 wraps a JSON-shaped-but-invalid body (e.g. an unknown enum constant, a string where a number was
     * expected) as an {@link HttpMessageNotReadableException} whose cause is a {@link DatabindException} carrying a
     * non-empty {@link JacksonException#getPath() path}; a JSON syntax error has no such cause, so it falls through
     * to the generic "malformed" detail instead of naming a field.
     */
    private static Optional<InvalidField> describeMalformedJson(@Nullable Throwable cause) {
        if (!(cause instanceof DatabindException databindException) || databindException.getPath().isEmpty()) {
            return Optional.empty();
        }
        String field = dottedPath(databindException.getPath());
        String message = "invalid value";
        if (databindException instanceof MismatchedInputException mismatchedInput
                && mismatchedInput.getTargetType() != null && mismatchedInput.getTargetType().isEnum()) {
            message = "invalid value; accepted values: " + acceptedValues(mismatchedInput.getTargetType());
        }
        return Optional.of(new InvalidField(field, message));
    }

    private static String dottedPath(List<JacksonException.Reference> path) {
        StringBuilder dottedPath = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getIndex() >= 0) {
                dottedPath.append('[').append(reference.getIndex()).append(']');
            } else {
                if (!dottedPath.isEmpty()) {
                    dottedPath.append('.');
                }
                dottedPath.append(reference.getPropertyName());
            }
        }
        return dottedPath.toString();
    }

    private static String acceptedValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
    }
}
