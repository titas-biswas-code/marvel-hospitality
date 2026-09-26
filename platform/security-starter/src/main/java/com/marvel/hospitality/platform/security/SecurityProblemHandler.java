package com.marvel.hospitality.platform.security;

import com.marvel.hospitality.platform.security.PropertyAccess.PropertyAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 9457 problem details for security failures, in the same {@code type}/{@code code} shape as every other
 * error (contracts/rest-api.md). Needed because the security filter chain runs before Spring MVC, so
 * {@code @ExceptionHandler} never sees these, and Spring's defaults answer 401/403 with an empty body.
 * <ul>
 *   <li>{@code 401 UNAUTHENTICATED}: missing, malformed, expired or wrongly signed token. Sets {@code WWW-Authenticate}
 *       per RFC 6750 itself: Spring Security 7.1's {@code BearerTokenAuthenticationEntryPoint} always advertises an
 *       RFC 9728 {@code resource_metadata} URL we do not serve.</li>
 *   <li>{@code 403 FORBIDDEN_PROPERTY}: the path's property is not in the token; {@code 403 FORBIDDEN}: any other
 *       denial (missing role). Method-security denials arrive here via {@code ExceptionTranslationFilter}, so an MVC
 *       {@code @ExceptionHandler} must never swallow {@link AccessDeniedException}.</li>
 * </ul>
 * Clients get a generic detail; the precise reason is logged at DEBUG.
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String FORBIDDEN_PROPERTY = "FORBIDDEN_PROPERTY";

    static final String TYPE_PREFIX = "https://marvel-hospitality/problems/";

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final JsonMapper jsonMapper;

    public SecurityProblemHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.debug("Unauthenticated request to {}: {}", request.getRequestURI(), exception.getMessage());
        String detail;
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + oauth2.getError().getErrorCode() + "\"");
            detail = exception instanceof InvalidBearerTokenException
                    ? "The bearer token is invalid, expired or not signed by the trusted issuer."
                    : "The bearer token could not be read.";
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            detail = "A bearer token is required.";
        }
        write(request, response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED, detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.debug("Access denied to {}: {}", request.getRequestURI(), exception.getMessage());
        if (exception instanceof PropertyAccessDeniedException propertyDenied) {
            write(request, response, HttpStatus.FORBIDDEN, FORBIDDEN_PROPERTY,
                    "Not entitled to act on property " + propertyDenied.propertyId() + ".");
        } else {
            write(request, response, HttpStatus.FORBIDDEN, FORBIDDEN,
                    "The token lacks the role required for this operation.");
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(jsonMapper.writeValueAsBytes(problem));
    }
}
