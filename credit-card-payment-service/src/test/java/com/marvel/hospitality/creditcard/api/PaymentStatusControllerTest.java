package com.marvel.hospitality.creditcard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.creditcard.config.CreditCardStubProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every branch of the deterministic stub (docs/contracts/credit-card-payment-api.yaml, ADR-0011): prefix
 * routing, the two 200 outcomes, 404, 500 and the two 400 cases handled by
 * {@link PaymentStatusExceptionHandling}. The clock is fixed so {@code lastUpdateDate} is asserted exactly,
 * and {@code slow-delay} is overridden to 300ms so the SLOW-prefix test does not actually wait 5 seconds.
 */
@WebMvcTest(controllers = {PaymentStatusController.class, PaymentStatusExceptionHandling.class})
@EnableConfigurationProperties(CreditCardStubProperties.class)
@TestPropertySource(properties = "credit-card-stub.slow-delay=300ms")
class PaymentStatusControllerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-26T10:15:30Z");
    private static final Duration CONFIGURED_SLOW_DELAY = Duration.ofMillis(300);

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    @Test
    void returnsConfirmedForOkPrefix() throws Exception {
        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"OK123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.lastUpdateDate").value(FIXED_INSTANT.toString()));
    }

    @Test
    void returnsRejectedForRejPrefix() throws Exception {
        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"REJ123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.lastUpdateDate").value(FIXED_INSTANT.toString()));
    }

    @Test
    void returns404ForUnknownReference() throws Exception {
        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"DL123456789\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Payment not found"));
    }

    @Test
    void returns500ForErrPrefix() throws Exception {
        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"ERR123456789\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    @Test
    void slowPrefixDelaysResponse() throws Exception {
        long startNanos = System.nanoTime();

        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"SLOW123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        assertThat(elapsed).isGreaterThanOrEqualTo(CONFIGURED_SLOW_DELAY);
    }

    @Test
    void returns400ForBlankReference() throws Exception {
        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void returns400ForMissingBody() throws Exception {
        mockMvc.perform(post("/credit-card-payment-api/payment-status")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
