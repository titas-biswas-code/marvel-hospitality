package com.marvel.hospitality.platform.problem.testapp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that throw exactly the exceptions {@code FallbackProblemAdviceTest} exercises. Not annotated
 * {@code @Validated}: Spring MVC validates constrained {@code @RequestParam}/{@code @PathVariable} parameters on
 * its own since Spring Framework 6.1, producing {@code HandlerMethodValidationException}; adding {@code @Validated}
 * on top would additionally proxy the controller for AOP-based method validation, which intercepts the call before
 * MVC's own handling and throws the raw {@code jakarta.validation.ConstraintViolationException} instead.
 */
@RestController
public class ProblemTestController {

    public static final String CUSTOM = "/test/custom";
    public static final String RUNTIME = "/test/runtime";
    public static final String CONSTRAINT = "/test/constraint";
    public static final String ACCESS_DENIED = "/test/access-denied";
    public static final String VALIDATED_BODY = "/test/validated";
    public static final String VALIDATED_PARAM = "/test/validated-param";

    @GetMapping(CUSTOM)
    String custom() {
        throw new ServiceSpecificException("Handled by the service's own advice.");
    }

    @GetMapping(RUNTIME)
    String runtime() {
        // The real message is never sent to the client; asserted on to prove that.
        throw new IllegalStateException("boom - sensitive internal detail");
    }

    @GetMapping(CONSTRAINT)
    String constraint() {
        // Stands in for a real, unmapped DB constraint violation; no real DB needed for this advice test.
        throw new DataIntegrityViolationException("duplicate key", new RuntimeException("constraint violation"));
    }

    @GetMapping(ACCESS_DENIED)
    String accessDenied() {
        throw new AccessDeniedException("Not entitled.");
    }

    @PostMapping(VALIDATED_BODY)
    String validatedBody(@Valid @RequestBody ValidatedRequest body) {
        return body.name();
    }

    @GetMapping(VALIDATED_PARAM)
    String validatedParam(@RequestParam @NotBlank String name) {
        return name;
    }
}
