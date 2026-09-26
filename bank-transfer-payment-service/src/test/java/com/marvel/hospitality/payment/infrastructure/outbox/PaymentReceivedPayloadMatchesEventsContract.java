package com.marvel.hospitality.payment.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.payment.MockJwtDecoderConfiguration;
import com.marvel.hospitality.payment.TestcontainersConfiguration;
import com.marvel.hospitality.payment.domain.BankTransaction;
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
 * This test replicates docs/contracts/events.md's {@code bank-transfer-payment-update} example on purpose (the
 * human's rule: every test owns its own data, even if that duplicates a doc). {@link PaymentReceivedPayload} is
 * serialised with the application's own {@link JsonMapper} bean (same context as the other {@code @SpringBootTest}s
 * in this service), so the real Jackson configuration ({@code spring.jackson.write.write-bigdecimal-as-plain},
 * ADR-0016) is exercised, not a hand-built mapper.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class PaymentReceivedPayloadMatchesEventsContract {

    private static final Path FIXTURE = Path.of("src/test/resources/contracts/bank-transfer-payment-update.v1.json");

    @Autowired
    private JsonMapper jsonMapper;

    private static BankTransaction contractExampleTransaction() {
        return new BankTransaction(
                UUID.fromString("5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f"),
                "BANK-TX-000123",
                "NL91ABNA0417164300",
                "A. Lovelace",
                new BigDecimal("120.00"),
                "EUR",
                "1401541457 P4145478",
                Instant.parse("2026-10-01T09:15:00Z"),
                Instant.parse("2026-10-01T09:15:02Z"));
    }

    private JsonNode fixtureTree() throws IOException {
        return jsonMapper.readTree(Files.readString(FIXTURE));
    }

    @Test
    void payloadHasExactlyTheContractFieldNames() throws IOException {
        JsonNode payload = jsonMapper.valueToTree(PaymentReceivedPayload.from(contractExampleTransaction()));

        Set<String> payloadFieldNames = Set.copyOf(payload.propertyNames());
        Set<String> fixtureFieldNames = Set.copyOf(fixtureTree().propertyNames());

        assertThat(payloadFieldNames).containsExactlyInAnyOrderElementsOf(fixtureFieldNames);
        assertThat(payloadFieldNames).containsExactlyInAnyOrder(
                "paymentId", "debtorAccountnumber", "amountReceived", "transactionDescription");
    }

    @Test
    void payloadFieldTypesMatchTheContract() {
        JsonNode payload = jsonMapper.valueToTree(PaymentReceivedPayload.from(contractExampleTransaction()));

        assertThat(payload.get("paymentId").isString()).isTrue();
        assertThat(payload.get("debtorAccountnumber").isString()).isTrue();
        assertThat(payload.get("transactionDescription").isString()).isTrue();
        assertThat(payload.get("amountReceived").isNumber()).isTrue();
    }

    @Test
    void payloadSerialisesTheContractExampleExactly() throws IOException {
        String serialised = jsonMapper.writeValueAsString(PaymentReceivedPayload.from(contractExampleTransaction()));

        assertThat(jsonMapper.readTree(serialised)).isEqualTo(fixtureTree());
        // Byte for byte, scale 2, plain notation (never scientific): the whole point of write-bigdecimal-as-plain.
        assertThat(serialised).contains("\"amountReceived\":120.00");
    }
}
