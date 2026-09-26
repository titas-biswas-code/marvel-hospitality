package com.marvel.hospitality.creditcard.api;

import com.marvel.hospitality.creditcard.config.CreditCardStubProperties;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements {@code src/main/resources/openapi/credit-card-payment-api.yaml} verbatim (ADR-0011): this is an
 * in-memory stub, not a real payment gateway, so the "business logic" is a deterministic mapping from the
 * {@code paymentReference} prefix to an outcome — no persistence, no state. The prefix contract (documented
 * in rest-api.md's credit-card-payment-service section) lets a demo or manual test drive every branch of the
 * reservation service's credit-card client (confirmed, rejected, not-found, error, slow/timeout) against the real
 * stub; the reservation service's own automated tests use WireMock instead.
 */
@RestController
@RequestMapping("/credit-card-payment-api")
class PaymentStatusController {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusController.class);

    private static final String CONFIRMED_PREFIX = "OK";
    private static final String REJECTED_PREFIX = "REJ";
    private static final String SLOW_PREFIX = "SLOW";
    private static final String ERROR_PREFIX = "ERR";

    private final Clock clock;
    private final CreditCardStubProperties properties;

    PaymentStatusController(Clock clock, CreditCardStubProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    @PostMapping("/payment-status")
    ResponseEntity<Object> retrievePaymentStatus(@Valid @RequestBody PaymentStatusRetrievalRequest request) {
        String reference = request.paymentReference();

        if (reference.startsWith(CONFIRMED_PREFIX)) {
            return confirmed(reference, "confirmed");
        }
        if (reference.startsWith(REJECTED_PREFIX)) {
            return rejected(reference);
        }
        if (reference.startsWith(SLOW_PREFIX)) {
            sleep(properties.slowDelay());
            return confirmed(reference, "confirmed-after-delay");
        }
        if (reference.startsWith(ERROR_PREFIX)) {
            return internalServerError(reference);
        }
        return notFound(reference);
    }

    private ResponseEntity<Object> confirmed(String reference, String outcome) {
        log.atInfo().addKeyValue("paymentReference", reference).addKeyValue("outcome", outcome).log("payment-status");
        return ResponseEntity.ok(new PaymentStatusResponse(clock.instant(), PaymentStatus.CONFIRMED));
    }

    private ResponseEntity<Object> rejected(String reference) {
        log.atInfo().addKeyValue("paymentReference", reference).addKeyValue("outcome", "rejected").log("payment-status");
        return ResponseEntity.ok(new PaymentStatusResponse(clock.instant(), PaymentStatus.REJECTED));
    }

    private ResponseEntity<Object> notFound(String reference) {
        log.atInfo().addKeyValue("paymentReference", reference).addKeyValue("outcome", "not-found").log("payment-status");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Payment not found"));
    }

    private ResponseEntity<Object> internalServerError(String reference) {
        log.atInfo().addKeyValue("paymentReference", reference).addKeyValue("outcome", "error").log("payment-status");
        return ResponseEntity.internalServerError().body(new ErrorResponse("Internal server error"));
    }

    // Blocking sleep is deliberate: it simulates a slow upstream gateway for the reservation service's
    // resilience tests (ADR-0011). Virtual threads (spring.threads.virtual.enabled) make this cheap.
    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating a slow payment gateway", e);
        }
    }
}
