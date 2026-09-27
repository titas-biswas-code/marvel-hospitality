package com.marvel.hospitality.notification.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.notification.MockJwtDecoderConfiguration;
import com.marvel.hospitality.notification.TestcontainersConfiguration;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer side of the {@code reservation-status-changed} contract: events.md's example value (copied to
 * src/test/resources/contracts, since nothing may read docs/) is read with the same {@link JsonMapper} the Kafka
 * message converter uses and passes the listener's {@code @Valid} constraints. Same cached context as the other
 * non-listening tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class ReservationStatusChangedMessageMatchesEventsContract {

    private static final String EXAMPLE = "/contracts/reservation-status-changed.v1.json";

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Validator validator;

    @Test
    void contractExampleIsReadAndValid() throws IOException {
        ReservationStatusChangedMessage message = read(example());

        assertThat(validator.validate(message)).isEmpty();
        // Record equality compares BigDecimal with its scale: 240.00 must arrive as 240.00, not 240.0.
        assertThat(message).isEqualTo(new ReservationStatusChangedMessage("P4145478", "AMS01", "Ada Lovelace", "201",
                LocalDate.parse("2027-10-10"), LocalDate.parse("2027-10-12"), "BANK_TRANSFER", null,
                "PENDING_PAYMENT", null, new BigDecimal("240.00"), new BigDecimal("0.00"), "EUR",
                Instant.parse("2027-10-07T22:00:00Z"), Instant.parse("2026-09-26T10:00:00Z")));
    }

    @Test
    void fieldsAddedByTheProducerLaterAreIgnored() throws IOException {
        String value = example().replaceFirst("\\{", "{\"loyaltyTier\": \"GOLD\",");

        assertThat(validator.validate(read(value))).isEmpty();
    }

    /** The example as text: read straight into the record like the Kafka converter does, never via a JSON tree. */
    private String example() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(EXAMPLE)) {
            assertThat(in).as(EXAMPLE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ReservationStatusChangedMessage read(String value) {
        return jsonMapper.readValue(value, ReservationStatusChangedMessage.class);
    }
}
