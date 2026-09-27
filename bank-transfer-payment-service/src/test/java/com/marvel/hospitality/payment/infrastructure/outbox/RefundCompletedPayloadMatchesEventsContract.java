package com.marvel.hospitality.payment.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.payment.MockJwtDecoderConfiguration;
import com.marvel.hospitality.payment.TestcontainersConfiguration;
import com.marvel.hospitality.payment.application.RefundCompletion;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This test replicates docs/contracts/events.md's {@code refund-completed} example on purpose (the human's rule:
 * every test owns its own data, even if that duplicates a doc). {@link RefundCompletedPayload} is serialised with
 * the application's own {@link JsonMapper} bean (same context as the other {@code @SpringBootTest}s in this
 * service), so the real Jackson configuration ({@code spring.jackson.write.write-bigdecimal-as-plain}, ADR-0016) is
 * exercised, not a hand-built mapper.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class RefundCompletedPayloadMatchesEventsContract {

    private static final Path FIXTURE = Path.of("src/test/resources/contracts/refund-completed.v1.json");

    @Autowired
    private JsonMapper jsonMapper;

    private static RefundCompletion contractExampleCompletion() {
        return new RefundCompletion(
                UUID.fromString("d3b07384-d9a0-4c9b-8e2f-6f1d2c3b4a59"),
                UUID.fromString("5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f"),
                "P4145478",
                "AMS01",
                new BigDecimal("30.00"),
                "EUR",
                true,
                null,
                Instant.parse("2026-10-01T09:16:05Z"));
    }

    private JsonNode fixtureTree() throws IOException {
        return jsonMapper.readTree(Files.readString(FIXTURE));
    }

    @Test
    void payloadHasExactlyTheContractFieldNames() throws IOException {
        JsonNode payload = jsonMapper.valueToTree(RefundCompletedPayload.from(contractExampleCompletion()));

        Set<String> payloadFieldNames = Set.copyOf(payload.propertyNames());
        Set<String> fixtureFieldNames = Set.copyOf(fixtureTree().propertyNames());

        assertThat(payloadFieldNames).containsExactlyInAnyOrderElementsOf(fixtureFieldNames);
        assertThat(payloadFieldNames).containsExactlyInAnyOrder(
                "refundId", "paymentId", "reservationId", "propertyId", "amount", "currency", "status",
                "failureReason", "completedAt");
    }

    @Test
    void payloadFieldTypesMatchTheContract() {
        JsonNode payload = jsonMapper.valueToTree(RefundCompletedPayload.from(contractExampleCompletion()));

        assertThat(payload.get("refundId").isString()).isTrue();
        assertThat(payload.get("paymentId").isString()).isTrue();
        assertThat(payload.get("reservationId").isString()).isTrue();
        assertThat(payload.get("propertyId").isString()).isTrue();
        assertThat(payload.get("amount").isNumber()).isTrue();
        assertThat(payload.get("currency").isString()).isTrue();
        assertThat(payload.get("status").isString()).isTrue();
        assertThat(payload.get("failureReason").isNull()).isTrue();
        assertThat(payload.get("completedAt").isString()).isTrue();
    }

    @Test
    void payloadSerialisesTheContractExampleExactly() throws IOException {
        String serialised = jsonMapper.writeValueAsString(RefundCompletedPayload.from(contractExampleCompletion()));

        assertThat(jsonMapper.readTree(serialised)).isEqualTo(fixtureTree());
        // Byte for byte, scale 2, plain notation (never scientific): the whole point of write-bigdecimal-as-plain.
        assertThat(serialised).contains("\"amount\":30.00");
        assertThat(serialised).contains("\"failureReason\":null");
    }

    @Test
    void aFailedRefundCarriesItsFailureReasonAndNoOtherStatus() {
        RefundCompletion failed = new RefundCompletion(UUID.randomUUID(), UUID.randomUUID(), "P4145478", "AMS01",
                new BigDecimal("30.00"), "EUR", false, "CREDITOR_ACCOUNT_REJECTED", Instant.parse("2026-10-01T09:16:05Z"));

        JsonNode payload = jsonMapper.valueToTree(RefundCompletedPayload.from(failed));

        assertThat(payload.get("status").asString()).isEqualTo("FAILED");
        assertThat(payload.get("failureReason").asString()).isEqualTo("CREDITOR_ACCOUNT_REJECTED");
    }
}
