package com.marvel.hospitality.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.payment.MockJwtDecoderConfiguration;
import com.marvel.hospitality.payment.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * The bank webhook end to end (rest-api.md, ADR-0014): a real Postgres behind {@link MockMvc}, so the outbox row and
 * the ledger row are asserted straight off the database, not off a mock. Same cached context (and so the same
 * Postgres container) as the other {@code @SpringBootTest}s in this service; every test isolates itself with its own
 * {@code bankTransactionRef}, never by truncating the shared tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class BankTransactionControllerTest {

    private static final String QUOTED_FIELD_FORMAT = "\"%s\":\"([^\"]*)\"";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private static JwtRequestPostProcessor ingestJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("bank:ingest"));
    }

    private static JwtRequestPostProcessor readJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("bank:read"));
    }

    private static String uniqueRef() {
        return "BANK-TX-" + UUID.randomUUID();
    }

    private static String ingestRequestJson(String ref, String amount, String currency) {
        return """
                {
                  "bankTransactionRef": "%s",
                  "debtorAccountNumber": "NL91ABNA0417164300",
                  "debtorName": "A. Lovelace",
                  "amount": %s,
                  "currency": "%s",
                  "remittanceInformation": "1401541457 P4145478",
                  "bookedAt": "2026-10-01T09:15:00Z"
                }""".formatted(ref, amount, currency);
    }

    /** Pulls a top-level quoted string field out of a JSON body without pulling in a JSON-path library. */
    private static String extractField(String json, String field) {
        Matcher matcher = Pattern.compile(QUOTED_FIELD_FORMAT.formatted(field)).matcher(json);
        assertThat(matcher.find()).as("field '%s' present in %s", field, json).isTrue();
        return matcher.group(1);
    }

    private int countByRef(String ref) {
        return jdbc.sql("SELECT count(*) FROM bank_transaction WHERE bank_transaction_ref = :ref")
                .param("ref", ref).query(Integer.class).single();
    }

    private int countOutboxByPaymentId(String paymentId) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_type = 'payment' AND aggregate_id = :id")
                .param("id", paymentId).query(Integer.class).single();
    }

    @Test
    void ingestsNewTransactionAndWritesOutboxRowWithBriefFieldNames() throws Exception {
        String ref = uniqueRef();

        MvcResult result = mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestRequestJson(ref, "120.00", "EUR")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.bankTransactionRef").value(ref))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn();

        String paymentId = extractField(result.getResponse().getContentAsString(), "paymentId");
        assertThat(UUID.fromString(paymentId)).isNotNull();

        assertThat(countOutboxByPaymentId(paymentId)).isEqualTo(1);

        List<String> payloadKeys = jdbc.sql("SELECT jsonb_object_keys(payload) FROM outbox_event WHERE aggregate_id = :id")
                .param("id", paymentId)
                .query(String.class)
                .list();
        assertThat(payloadKeys).containsExactlyInAnyOrder(
                "paymentId", "debtorAccountnumber", "amountReceived", "transactionDescription");

        Map<String, Object> row = jdbc.sql("""
                        SELECT aggregate_type, event_type, event_version, topic, property_id, producer,
                               payload::text AS payload_text,
                               payload ->> 'paymentId' AS payload_payment_id,
                               payload ->> 'debtorAccountnumber' AS payload_debtor_account,
                               payload ->> 'amountReceived' AS payload_amount,
                               payload ->> 'transactionDescription' AS payload_description
                          FROM outbox_event WHERE aggregate_id = :id
                        """)
                .param("id", paymentId)
                .query()
                .singleRow();

        assertThat(row).containsEntry("aggregate_type", "payment")
                .containsEntry("event_type", "PaymentReceived")
                .containsEntry("event_version", 1)
                .containsEntry("topic", "bank-transfer-payment-update")
                .containsEntry("producer", "bank-transfer-payment-service")
                .containsEntry("payload_payment_id", paymentId)
                .containsEntry("payload_debtor_account", "NL91ABNA0417164300")
                .containsEntry("payload_amount", "120.00")
                .containsEntry("payload_description", "1401541457 P4145478");
        assertThat(row.get("property_id")).isNull();
        // Postgres renders jsonb text with a space after the colon; this also proves scale 2, plain notation.
        assertThat((String) row.get("payload_text")).contains("\"amountReceived\": 120.00");

        String raw = jdbc.sql("SELECT raw::text FROM bank_transaction WHERE payment_id = :id")
                .param("id", UUID.fromString(paymentId))
                .query(String.class)
                .single();
        assertThat(raw).contains(ref);
    }

    @Test
    void duplicateBankTransactionRefReturns200SameIdAndNoNewOutboxRow() throws Exception {
        String ref = uniqueRef();
        String requestJson = ingestRequestJson(ref, "75.50", "EUR");

        MvcResult first = mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isAccepted())
                .andReturn();
        String paymentId = extractField(first.getResponse().getContentAsString(), "paymentId");

        mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.bankTransactionRef").value(ref));

        assertThat(countOutboxByPaymentId(paymentId)).isEqualTo(1);
        assertThat(countByRef(ref)).isEqualTo(1);
    }

    @Test
    void rejectsNonEurCurrencyWith422() throws Exception {
        String ref = uniqueRef();

        mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestRequestJson(ref, "120.00", "USD")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/UNSUPPORTED_CURRENCY"));

        assertThat(countByRef(ref)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00"})
    void rejectsNonPositiveAmountWith400(String amount) throws Exception {
        String ref = uniqueRef();

        mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestRequestJson(ref, amount, "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));

        assertThat(countByRef(ref)).isZero();
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimalsWith400() throws Exception {
        String ref = uniqueRef();

        mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestRequestJson(ref, "120.123", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));

        assertThat(countByRef(ref)).isZero();
    }

    @Test
    void ingestRequiresBankIngestRole() throws Exception {
        String ref = uniqueRef();
        String requestJson = ingestRequestJson(ref, "50.00", "EUR");

        mvc.perform(post("/bank-transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(post("/bank-transactions").with(readJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(countByRef(ref)).isZero();
    }

    @Test
    void getReturnsTransactionForBankReadRole() throws Exception {
        String ref = uniqueRef();
        MvcResult ingestResult = mvc.perform(post("/bank-transactions").with(ingestJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestRequestJson(ref, "120.00", "EUR")))
                .andExpect(status().isAccepted())
                .andReturn();
        String paymentId = extractField(ingestResult.getResponse().getContentAsString(), "paymentId");

        MvcResult getResult = mvc.perform(get("/bank-transactions/{paymentId}", paymentId).with(readJwt()))
                .andExpect(status().isOk())
                .andReturn();

        // jsonPath parses numbers as double, which would hide a scale-2 rendering bug; assert on the raw body instead.
        String body = getResult.getResponse().getContentAsString();
        assertThat(body).contains("\"paymentId\":\"" + paymentId + "\"");
        assertThat(body).contains("\"bankTransactionRef\":\"" + ref + "\"");
        assertThat(body).contains("\"debtorAccountNumber\":\"NL91ABNA0417164300\"");
        assertThat(body).contains("\"amount\":120.00");
        assertThat(body).contains("\"currency\":\"EUR\"");
        assertThat(body).contains("\"remittanceInformation\":\"1401541457 P4145478\"");
    }

    @Test
    void getUnknownPaymentIdReturns404() throws Exception {
        mvc.perform(get("/bank-transactions/{paymentId}", UUID.randomUUID()).with(readJwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BANK_TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/BANK_TRANSACTION_NOT_FOUND"));
    }

    @Test
    void getRequiresBankReadRole() throws Exception {
        mvc.perform(get("/bank-transactions/{paymentId}", UUID.randomUUID()).with(ingestJwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void getWithNonUuidPaymentIdReturns400() throws Exception {
        mvc.perform(get("/bank-transactions/{paymentId}", "not-a-uuid").with(readJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
