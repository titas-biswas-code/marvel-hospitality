package com.marvel.hospitality.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.payment.MockJwtDecoderConfiguration;
import com.marvel.hospitality.payment.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /refunds/{refundId}} (rest-api.md, ADR-0014): rows are seeded straight into {@code bank_transaction}
 * and {@code refund_instruction} over JDBC (the write path is {@link
 * com.marvel.hospitality.payment.infrastructure.kafka.RefundRequestedConsumerIntegrationTest}'s job), so this class
 * only proves the read side and its security. Same cached context (and so the same Postgres container) as the other
 * {@code @SpringBootTest}s in this service; every test uses its own {@code refundId}, never truncates tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class RefundControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private static JwtRequestPostProcessor readJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("bank:read"));
    }

    private static JwtRequestPostProcessor ingestJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("bank:ingest"));
    }

    /** Seeds a {@code bank_transaction} row (the FK {@code refund_instruction} needs) and returns its paymentId. */
    private UUID seedBankTransaction() {
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO bank_transaction
                            (payment_id, bank_transaction_ref, debtor_account_number, debtor_name, amount, currency,
                             remittance_information, booked_at, received_at, raw)
                        VALUES
                            (:paymentId, :ref, :debtorAccountNumber, :debtorName, :amount, :currency, :info,
                             :bookedAt, :receivedAt, CAST(:raw AS jsonb))
                        """)
                .param("paymentId", paymentId)
                .param("ref", "BANK-TX-REFUND-CONTROLLER-" + paymentId)
                .param("debtorAccountNumber", "NL91ABNA0417164300")
                .param("debtorName", "A. Lovelace")
                .param("amount", new BigDecimal("120.00"))
                .param("currency", "EUR")
                .param("info", "1401541457 P4145478")
                .param("bookedAt", offsetDateTime("2026-10-01T09:15:00Z"))
                .param("receivedAt", offsetDateTime("2026-10-01T09:15:02Z"))
                .param("raw", "{}")
                .update();
        return paymentId;
    }

    private UUID seedRefundInstruction(String status, String failureReason) {
        UUID paymentId = seedBankTransaction();
        UUID refundId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO refund_instruction
                            (refund_id, payment_id, reservation_id, property_id, creditor_account_number, amount,
                             currency, reason, status, failure_reason, created_at, executed_at)
                        VALUES
                            (:refundId, :paymentId, :reservationId, :propertyId, :creditorAccountNumber, :amount,
                             :currency, :reason, :status, :failureReason, :createdAt, :executedAt)
                        """)
                .param("refundId", refundId)
                .param("paymentId", paymentId)
                .param("reservationId", "P4145478")
                .param("propertyId", "AMS01")
                .param("creditorAccountNumber", "NL91ABNA0417164300")
                .param("amount", new BigDecimal("30.00"))
                .param("currency", "EUR")
                .param("reason", "OVERPAYMENT")
                .param("status", status)
                .param("failureReason", failureReason, Types.VARCHAR)
                .param("createdAt", offsetDateTime("2026-10-02T11:40:11Z"))
                .param("executedAt", "EXECUTED".equals(status) ? offsetDateTime("2026-10-02T11:40:11Z") : null,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return refundId;
    }

    /** Raw {@link JdbcClient} inserts need {@link OffsetDateTime}: pgjdbc cannot infer a type for {@link Instant}. */
    private static OffsetDateTime offsetDateTime(String iso) {
        return Instant.parse(iso).atOffset(ZoneOffset.UTC);
    }

    @Test
    void getReturnsRefundForBankReadRole() throws Exception {
        UUID refundId = seedRefundInstruction("EXECUTED", null);

        String body = mvc.perform(get("/refunds/{refundId}", refundId).with(readJwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // jsonPath parses numbers as double, which would hide a scale-2 rendering bug; assert on the raw body instead.
        assertThat(body).contains("\"refundId\":\"" + refundId + "\"");
        assertThat(body).contains("\"amount\":30.00");
        assertThat(body).contains("\"currency\":\"EUR\"");
        assertThat(body).contains("\"reason\":\"OVERPAYMENT\"");
        assertThat(body).contains("\"status\":\"EXECUTED\"");
        assertThat(body).contains("\"failureReason\":null");
    }

    @Test
    void getUnknownRefundIdReturns404() throws Exception {
        mvc.perform(get("/refunds/{refundId}", UUID.randomUUID()).with(readJwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/REFUND_NOT_FOUND"));
    }

    @Test
    void getWithNonUuidRefundIdReturns400() throws Exception {
        mvc.perform(get("/refunds/{refundId}", "not-a-uuid").with(readJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getRequiresBankReadRole() throws Exception {
        mvc.perform(get("/refunds/{refundId}", UUID.randomUUID()).with(ingestJwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void getWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/refunds/{refundId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
