package com.marvel.hospitality.reservation.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.resetAllRequests;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.CreditCardPaymentClient;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.wiremock.spring.EnableWireMock;

/**
 * End-to-end credit-card reservation flow against a real Postgres and a real HTTP call to a WireMock stand-in for
 * credit-card-payment-service (ADR-0011): retry, circuit breaker, 4xx-vs-5xx handling and "nothing persisted on
 * failure" all only prove themselves with a real socket in the loop, so this is deliberately not a unit test.
 *
 * <p>{@code wiremock-spring-boot}'s {@code @EnableWireMock} publishes the running stub server's base URL as the
 * {@code wiremock.server.baseUrl} property (confirmed from the {@code ConfigureWireMock} annotation's
 * {@code baseUrlProperties} default in the 4.4.2 jar) before context refresh, so the placeholder below resolves
 * correctly. The same extension resets stub mappings and the request journal before every test method
 * ({@code WireMockTestExecutionListener.beforeTestMethod} calls {@code WireMockServer.resetAll()}), so no manual
 * {@code WireMock.reset()} is needed between tests here; only the circuit breaker (a singleton across this whole
 * suite) needs its own reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "credit-card.client.base-url=${wiremock.server.baseUrl}/credit-card-payment-api")
@AutoConfigureMockMvc
@EnableWireMock
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class CreditCardReservationIntegrationTest {

    private static final String PAYMENT_STATUS_PATH = "/credit-card-payment-api/payment-status";

    private static final String RESILIENCE_INSTANCE = "creditCardPayment";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @Autowired
    private CreateReservationUseCase createReservationUseCase;

    @MockitoSpyBean
    private CreditCardPaymentClient creditCardPaymentClient;

    @BeforeEach
    void resetCircuitBreaker() {
        // The circuit breaker is a singleton for the whole suite; a test that trips it must not leak that state.
        circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE).reset();
    }

    private static SimpleGrantedAuthority writeAuthority() {
        return new SimpleGrantedAuthority("reservation:write");
    }

    private static String creditCardRequestJson(
            String roomNumber, RoomSegment segment, LocalDate start, LocalDate end, String paymentReference) {
        return """
                {
                  "customerName": "Ada Lovelace",
                  "roomNumber": "%s",
                  "startDate": "%s",
                  "endDate": "%s",
                  "roomSegment": "%s",
                  "paymentMode": "CREDIT_CARD",
                  "paymentReference": "%s"
                }""".formatted(roomNumber, start, end, segment, paymentReference);
    }

    private MvcResult postCreditCardReservation(
            String roomNumber, RoomSegment segment, LocalDate start, LocalDate end, String paymentReference) throws Exception {
        return mvc.perform(post("/properties/AMS01/reservations")
                        .with(jwt().authorities(writeAuthority()).jwt(j -> j.claim("properties", List.of("AMS01"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditCardRequestJson(roomNumber, segment, start, end, paymentReference)))
                .andReturn();
    }

    private long reservationCount(String roomNumber, LocalDate start) {
        return jdbc.sql("SELECT count(*) FROM reservation WHERE property_id = :p AND room_number = :r AND start_date = :s")
                .param("p", "AMS01").param("r", roomNumber).param("s", start)
                .query(Long.class).single();
    }

    private long outboxCountForProperty() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE property_id = :p")
                .param("p", "AMS01").query(Long.class).single();
    }

    @Test
    void confirmsCreditCardReservationWhenPaymentConfirmed() throws Exception {
        LocalDate start = LocalDate.parse("2031-01-10");
        LocalDate end = LocalDate.parse("2031-01-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH))
                .willReturn(okJson("""
                        {"status":"CONFIRMED","lastUpdateDate":"2031-01-01T00:00:00Z"}""")));

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "OK-CONFIRMED-1");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body).contains("\"status\":\"CONFIRMED\"").contains("\"paymentReference\":\"OK-CONFIRMED-1\"");

        Map<String, Object> row = jdbc.sql("""
                        SELECT status, payment_mode FROM reservation
                        WHERE property_id = :p AND room_number = :r AND start_date = :s
                        """)
                .param("p", "AMS01").param("r", "301").param("s", start)
                .query().singleRow();
        assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("payment_mode", "CREDIT_CARD");

        String reservationId = jdbc.sql("SELECT reservation_id FROM reservation WHERE property_id=:p AND room_number=:r AND start_date=:s")
                .param("p", "AMS01").param("r", "301").param("s", start).query(String.class).single();
        Long outboxRows = jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :id")
                .param("id", reservationId).query(Long.class).single();
        assertThat(outboxRows).isEqualTo(1L);
        String payloadStatus = jdbc.sql("SELECT payload->>'status' FROM outbox_event WHERE aggregate_id = :id")
                .param("id", reservationId).query(String.class).single();
        assertThat(payloadStatus).isEqualTo("CONFIRMED");

        verify(1, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)).withRequestBody(containing("OK-CONFIRMED-1")));
    }

    @Test
    void returns422AndPersistsNothingWhenPaymentRejected() throws Exception {
        LocalDate start = LocalDate.parse("2031-02-10");
        LocalDate end = LocalDate.parse("2031-02-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH))
                .willReturn(okJson("""
                        {"status":"REJECTED","lastUpdateDate":"2031-02-01T00:00:00Z"}""")));
        long outboxBefore = outboxCountForProperty();

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "REJ-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"PAYMENT_REJECTED\"");
        assertThat(reservationCount("301", start)).isZero();
        assertThat(outboxCountForProperty()).isEqualTo(outboxBefore);
    }

    @Test
    void returns422AndPersistsNothingWhenPaymentNotFound() throws Exception {
        LocalDate start = LocalDate.parse("2031-03-10");
        LocalDate end = LocalDate.parse("2031-03-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":"Payment not found"}""")));
        long outboxBefore = outboxCountForProperty();

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "UNKNOWN-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"PAYMENT_REJECTED\"");
        assertThat(reservationCount("301", start)).isZero();
        assertThat(outboxCountForProperty()).isEqualTo(outboxBefore);
        // 404 is a definite answer, not a failure: never retried.
        verify(1, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
    }

    @Test
    void retriesOnceOn500ThenSucceeds() throws Exception {
        LocalDate start = LocalDate.parse("2031-04-10");
        LocalDate end = LocalDate.parse("2031-04-12");
        String scenario = "retry-once";
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH)).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second-attempt"));
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH)).inScenario(scenario)
                .whenScenarioStateIs("second-attempt")
                .willReturn(okJson("""
                        {"status":"CONFIRMED","lastUpdateDate":"2031-04-01T00:00:00Z"}""")));

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "RETRY-OK-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"CONFIRMED\"");
        assertThat(reservationCount("301", start)).isEqualTo(1L);
        verify(2, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
    }

    @Test
    void returns503AfterRetriesExhaustedOn500() throws Exception {
        LocalDate start = LocalDate.parse("2031-05-10");
        LocalDate end = LocalDate.parse("2031-05-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH)).willReturn(aResponse().withStatus(500)));
        long outboxBefore = outboxCountForProperty();

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "ALWAYS-500");

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("5");
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"PAYMENT_SERVICE_UNAVAILABLE\"");
        assertThat(reservationCount("301", start)).isZero();
        assertThat(outboxCountForProperty()).isEqualTo(outboxBefore);
        verify(3, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
    }

    @Test
    void returns503OnReadTimeout() throws Exception {
        LocalDate start = LocalDate.parse("2031-06-10");
        LocalDate end = LocalDate.parse("2031-06-12");
        // read-timeout is 2s (application.yml); a 3s fixed delay makes every attempt time out.
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH))
                .willReturn(okJson("""
                        {"status":"CONFIRMED","lastUpdateDate":"2031-06-01T00:00:00Z"}""")
                        .withFixedDelay(3000)));
        long outboxBefore = outboxCountForProperty();

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "TIMEOUT-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"PAYMENT_SERVICE_UNAVAILABLE\"");
        assertThat(reservationCount("301", start)).isZero();
        assertThat(outboxCountForProperty()).isEqualTo(outboxBefore);
        verify(3, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
    }

    @Test
    void doesNotRetryOn4xx() throws Exception {
        LocalDate start = LocalDate.parse("2031-07-10");
        LocalDate end = LocalDate.parse("2031-07-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH)).willReturn(aResponse().withStatus(400)));

        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "BAD-REQUEST-1");

        // Our own request was malformed as far as the payment service is concerned; that is a bug in this
        // service's client, not the payment service being unhealthy, so it is a 500, not 422/503.
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"INTERNAL_ERROR\"");
        verify(1, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void circuitOpensAfterFailureThresholdAndFailsFast() throws Exception {
        LocalDate start = LocalDate.parse("2031-08-10");
        LocalDate end = LocalDate.parse("2031-08-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH)).willReturn(aResponse().withStatus(500)));
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);

        // Each failing request records up to 3 calls (1 + 2 retries); minimum-number-of-calls is 10, so with a
        // 100% failure rate the circuit is expected to open partway through the 4th request. Bounded loop so a
        // config regression fails the test instead of hanging.
        int bound = 6;
        int attempt = 0;
        while (circuitBreaker.getState() != CircuitBreaker.State.OPEN && attempt < bound) {
            attempt++;
            postCreditCardReservation("301", RoomSegment.LARGE, start, end, "CB-TRIP-" + attempt);
        }

        assertThat(circuitBreaker.getState())
                .as("circuit breaker should have opened within %d requests", bound)
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(attempt).isLessThanOrEqualTo(bound);

        // Fail-fast: the open circuit must short-circuit before any network call is made.
        resetAllRequests();
        MvcResult result = postCreditCardReservation("301", RoomSegment.LARGE, start, end, "CB-OPEN-CHECK");

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"PAYMENT_SERVICE_UNAVAILABLE\"");
        verify(0, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void returns409WhenRoomTakenAfterPaymentConfirmed() throws Exception {
        LocalDate start = LocalDate.parse("2031-09-10");
        LocalDate end = LocalDate.parse("2031-09-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH))
                .willReturn(okJson("""
                        {"status":"CONFIRMED","lastUpdateDate":"2031-09-01T00:00:00Z"}""")));

        // Simulates the race the use case documents: the lock-free isBooked() check passed, then someone else took
        // the room while the (slow) payment call was in flight, and only the real INSERT can catch it.
        doAnswer(invocation -> {
            createReservationUseCase.create(new CreateReservationCommand("AMS01", "Bruce Wayne", "201",
                    start, end, RoomSegment.MEDIUM, PaymentMode.CASH, null));
            return invocation.callRealMethod();
        }).when(creditCardPaymentClient).retrieveStatus(anyString());

        MvcResult result = postCreditCardReservation("201", RoomSegment.MEDIUM, start, end, "RACE-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"ROOM_UNAVAILABLE\"");
        verify(1, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));

        assertThat(reservationCount("201", start)).isEqualTo(1L);
        Map<String, Object> row = jdbc.sql("SELECT payment_mode FROM reservation WHERE property_id='AMS01' AND room_number='201' AND start_date = :s")
                .param("s", start).query().singleRow();
        assertThat(row).containsEntry("payment_mode", "CASH");

        String cashReservationId = jdbc.sql("SELECT reservation_id FROM reservation WHERE property_id='AMS01' AND room_number='201' AND start_date = :s")
                .param("s", start).query(String.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :id")
                .param("id", cashReservationId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void returns409WithoutCallingPaymentServiceWhenRoomAlreadyBooked() throws Exception {
        LocalDate start = LocalDate.parse("2031-10-10");
        LocalDate end = LocalDate.parse("2031-10-12");
        createReservationUseCase.create(new CreateReservationCommand(
                "AMS01", "Bruce Wayne", "202", start, end, RoomSegment.MEDIUM, PaymentMode.CASH, null));

        MvcResult result = postCreditCardReservation("202", RoomSegment.MEDIUM, start, end, "SHOULD-NOT-BE-CALLED");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"ROOM_UNAVAILABLE\"");
        verify(0, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
        assertThat(reservationCount("202", start)).isEqualTo(1L);
    }

    @Test
    void returns409WithoutCallingPaymentServiceWhenPaymentReferenceAlreadyUsed() throws Exception {
        LocalDate start = LocalDate.parse("2031-12-10");
        LocalDate end = LocalDate.parse("2031-12-12");
        stubFor(WireMock.post(urlEqualTo(PAYMENT_STATUS_PATH))
                .willReturn(okJson("""
                        {"status":"CONFIRMED","lastUpdateDate":"2031-12-01T00:00:00Z"}""")));
        assertThat(postCreditCardReservation("301", RoomSegment.LARGE, start, end, "OK-ONCE-ONLY")
                .getResponse().getStatus()).isEqualTo(201);
        resetAllRequests();

        // Same confirmed payment, different room: one card payment must not confirm two stays.
        MvcResult result = postCreditCardReservation("401", RoomSegment.EXTRA_LARGE, start, end, "OK-ONCE-ONLY");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"PAYMENT_REFERENCE_ALREADY_USED\"");
        verify(0, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
        assertThat(reservationCount("401", start)).isZero();
    }

    @Test
    void exposesCircuitBreakerHealthAndMetrics() {
        assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", RESILIENCE_INSTANCE).meters())
                .as("resilience4j.circuitbreaker.state meter tagged name=creditCardPayment")
                .isNotEmpty();

        HealthContributor contributor = healthContributorRegistry.getContributor("circuitBreakers");
        assertThat(contributor).as("Resilience4j circuit breakers health contributor").isNotNull();
    }

    @Test
    void returns400WhenCreditCardHasNoPaymentReference() throws Exception {
        // Sanity check that validation still runs ahead of any WireMock interaction in this fuller Spring context.
        String withoutReference = """
                {
                  "customerName": "Ada Lovelace",
                  "roomNumber": "301",
                  "startDate": "2031-11-10",
                  "endDate": "2031-11-12",
                  "roomSegment": "LARGE",
                  "paymentMode": "CREDIT_CARD"
                }""";

        mvc.perform(post("/properties/AMS01/reservations")
                        .with(jwt().authorities(writeAuthority()).jwt(j -> j.claim("properties", List.of("AMS01"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withoutReference))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(0, postRequestedFor(urlEqualTo(PAYMENT_STATUS_PATH)));
    }
}
