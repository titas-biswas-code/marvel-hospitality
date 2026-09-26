package com.marvel.hospitality.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.GetReservationUseCase;
import com.marvel.hospitality.reservation.application.CreditCardPaymentStatus;
import com.marvel.hospitality.reservation.application.PaymentReferenceAlreadyUsedException;
import com.marvel.hospitality.reservation.application.PaymentRejectedException;
import com.marvel.hospitality.reservation.application.PaymentServiceUnavailableException;
import com.marvel.hospitality.reservation.application.PropertyNotFoundException;
import com.marvel.hospitality.reservation.application.ReservationNotFoundException;
import com.marvel.hospitality.reservation.application.ReservationView;
import com.marvel.hospitality.reservation.application.RoomNotFoundException;
import com.marvel.hospitality.reservation.application.RoomUnavailableException;
import com.marvel.hospitality.reservation.domain.BankTransferLeadTimeTooShortException;
import com.marvel.hospitality.reservation.domain.BankTransferPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.CancellationReason;
import com.marvel.hospitality.reservation.domain.CashPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.InvalidStayException;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.NewReservation;
import com.marvel.hospitality.reservation.domain.PaymentDeadlinePolicy;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import com.marvel.hospitality.reservation.domain.RoomSegmentMismatchException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Web-layer slice test: real domain objects ({@link Reservation}, built exactly as {@code ReservationTest} builds
 * them) flow through {@link ReservationResponse#from}, only the use cases are mocked. Every authenticated request
 * uses spring-security-test's {@code jwt()} post-processor (PR-01 convention, see
 * {@code SecurityWiringTest}/{@code SecurityWebMvcSliceTest}).
 */
@WebMvcTest(controllers = {ReservationController.class, ReferenceDataController.class})
@Import({ReservationProblemAdvice.class, MockJwtDecoderConfiguration.class})
class ReservationControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC);
    private static final Property AMS01 =
            new Property("AMS01", "Marvel Amsterdam", ZoneId.of("Europe/Amsterdam"), "NL00MARV0000000001");
    private static final Room ROOM_101 = new Room("AMS01", "101", RoomSegment.MEDIUM);
    private static final Money NIGHTLY_RATE = Money.eur("120.00");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CreateReservationUseCase createReservationUseCase;

    @MockitoBean
    private GetReservationUseCase getReservationUseCase;

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor writeJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("reservation:write"))
                .jwt(j -> j.claim("properties", List.of("AMS01")));
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor readJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("reservation:read"))
                .jwt(j -> j.claim("properties", List.of("AMS01")));
    }

    private static ReservationView cashReservation() {
        NewReservation request = new NewReservation(ReservationId.of("P4145478"), "Ada Lovelace",
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), RoomSegment.MEDIUM, PaymentMode.CASH, null);
        Reservation reservation =
                Reservation.create(request, AMS01, ROOM_101, NIGHTLY_RATE, new CashPaymentModeHandler(), CLOCK);
        return new ReservationView(reservation, reservation.bankTransferInstructions(AMS01.bankAccountNumber()));
    }

    private static ReservationView bankTransferReservation() {
        NewReservation request = new NewReservation(ReservationId.of("P4145478"), "Ada Lovelace",
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), RoomSegment.MEDIUM, PaymentMode.BANK_TRANSFER, null);
        Reservation reservation = Reservation.create(
                request, AMS01, ROOM_101, NIGHTLY_RATE, new BankTransferPaymentModeHandler(new PaymentDeadlinePolicy()), CLOCK);
        return new ReservationView(reservation, reservation.bankTransferInstructions(AMS01.bankAccountNumber()));
    }

    private static String validCashRequestJson() {
        return """
                {
                  "customerName": "Ada Lovelace",
                  "roomNumber": "101",
                  "startDate": "2026-10-10",
                  "endDate": "2026-10-12",
                  "roomSegment": "MEDIUM",
                  "paymentMode": "CASH"
                }""";
    }

    private static String validBankTransferRequestJson() {
        return """
                {
                  "customerName": "Ada Lovelace",
                  "roomNumber": "101",
                  "startDate": "2026-10-10",
                  "endDate": "2026-10-12",
                  "roomSegment": "MEDIUM",
                  "paymentMode": "BANK_TRANSFER"
                }""";
    }

    @Test
    void createsCashReservationAndReturns201WithLocation() throws Exception {
        given(createReservationUseCase.create(any())).willReturn(cashReservation());

        MvcResult result = mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value("P4145478"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Location"))
                .endsWith("/properties/AMS01/reservations/P4145478");
        String body = result.getResponse().getContentAsString();
        // Jackson 3 + spring.jackson.write.write-bigdecimal-as-plain=true (ADR-0016, application.yml): never 2.4E+2.
        assertThat(body).contains("\"totalAmount\":240.00");
        assertThat(body).contains("\"amountReceived\":0.00");
        assertThat(body).contains("\"paymentDeadlineAt\":null");
        assertThat(body).contains("\"bankTransferInstructions\":null");
    }

    @Test
    void rejectsCreateWithoutWriteRole() throws Exception {
        mvc.perform(post("/properties/AMS01/reservations").with(readJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/FORBIDDEN"));
    }

    @Test
    void rejectsCreateForPropertyNotInClaim() throws Exception {
        mvc.perform(post("/properties/AMS01/reservations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("reservation:write"))
                                .jwt(j -> j.claim("properties", List.of("RTM01"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PROPERTY"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/FORBIDDEN_PROPERTY"));
    }

    @Test
    void getRequiresReadRoleAndPropertyAccess() throws Exception {
        given(getReservationUseCase.get("AMS01", "P4145478")).willReturn(cashReservation());

        // no read role -> FORBIDDEN
        mvc.perform(get("/properties/AMS01/reservations/P4145478").with(writeJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // wrong property -> FORBIDDEN_PROPERTY
        mvc.perform(get("/properties/AMS01/reservations/P4145478")
                        .with(jwt().authorities(new SimpleGrantedAuthority("reservation:read"))
                                .jwt(j -> j.claim("properties", List.of("RTM01")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PROPERTY"));

        // ok -> 200 with body
        mvc.perform(get("/properties/AMS01/reservations/P4145478").with(readJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value("P4145478"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // no token -> 401
        mvc.perform(get("/properties/AMS01/reservations/P4145478"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void createsBankTransferReservationWithDeadlineAndInstructions() throws Exception {
        given(createReservationUseCase.create(any())).willReturn(bankTransferReservation());

        MvcResult result = mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBankTransferRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentDeadlineAt").value("2026-10-07T22:00:00Z"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"bankTransferInstructions\":\"Transfer 240.00 EUR to NL00MARV0000000001 "
                + "with description '<your E2E id> P4145478'\"");
    }

    @Test
    void returns400WithFieldDetailsForInvalidPayload() throws Exception {
        String blankCustomerNameMissingStartDate = """
                {
                  "customerName": "",
                  "roomNumber": "101",
                  "endDate": "2026-10-12",
                  "roomSegment": "MEDIUM",
                  "paymentMode": "CASH"
                }""";

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankCustomerNameMissingStartDate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("customerName", "startDate")));

        given(createReservationUseCase.create(any()))
                .willThrow(new InvalidStayException("endDate", "endDate must be after startDate"));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));
    }

    @Test
    void returns404ForUnknownRoom() throws Exception {
        given(createReservationUseCase.create(any())).willThrow(new RoomNotFoundException("AMS01", "101"));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/ROOM_NOT_FOUND"));
    }

    @Test
    void returns422ForSegmentMismatch() throws Exception {
        given(createReservationUseCase.create(any()))
                .willThrow(new RoomSegmentMismatchException(RoomSegment.LARGE, RoomSegment.MEDIUM));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOM_SEGMENT_MISMATCH"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/ROOM_SEGMENT_MISMATCH"));
    }

    @Test
    void returns409ForRoomUnavailable() throws Exception {
        given(createReservationUseCase.create(any()))
                .willThrow(new RoomUnavailableException("AMS01", "101", new RuntimeException("23P01")));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOM_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/ROOM_UNAVAILABLE"));
    }

    @Test
    void returns422WhenBankTransferLeadTimeTooShort() throws Exception {
        given(createReservationUseCase.create(any()))
                .willThrow(new BankTransferLeadTimeTooShortException(Instant.parse("2026-09-26T22:00:00Z")));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBankTransferRequestJson()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BANK_TRANSFER_LEAD_TIME_TOO_SHORT"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/BANK_TRANSFER_LEAD_TIME_TOO_SHORT"));
    }

    @Test
    void referenceDataListsAllEnumValues() throws Exception {
        mvc.perform(get("/reference-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationStatuses").value(namesOf(ReservationStatus.values())))
                .andExpect(jsonPath("$.paymentModes").value(namesOf(PaymentMode.values())))
                .andExpect(jsonPath("$.roomSegments").value(namesOf(RoomSegment.values())))
                .andExpect(jsonPath("$.paymentMatchOutcomes").value(namesOf(PaymentMatchOutcome.values())))
                .andExpect(jsonPath("$.refundReasons").value(namesOf(RefundReason.values())))
                .andExpect(jsonPath("$.cancellationReasons").value(namesOf(CancellationReason.values())));
    }

    @SuppressWarnings("unchecked")
    private static Matcher<Iterable<? extends String>> namesOf(Enum<?>[] values) {
        return contains(Arrays.stream(values).map(Enum::name).toArray(String[]::new));
    }

    private static String creditCardRequestJson(String paymentReferenceJson) {
        return validCashRequestJson().replace("\"CASH\"", "\"CREDIT_CARD\", \"paymentReference\": " + paymentReferenceJson);
    }

    @Test
    void returns422PaymentRejectedWhenCardPaymentIsRejected() throws Exception {
        given(createReservationUseCase.create(any()))
                .willThrow(new PaymentRejectedException("REJ-1", CreditCardPaymentStatus.REJECTED));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditCardRequestJson("\"REJ-1\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_REJECTED"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/PAYMENT_REJECTED"));
    }

    @Test
    void returns409WhenPaymentReferenceAlreadyUsed() throws Exception {
        given(createReservationUseCase.create(any()))
                .willThrow(new PaymentReferenceAlreadyUsedException(PaymentMode.CREDIT_CARD, "OK-123"));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditCardRequestJson("\"OK-123\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFERENCE_ALREADY_USED"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/PAYMENT_REFERENCE_ALREADY_USED"));
    }

    @Test
    void returns503WithRetryAfterWhenPaymentServiceUnavailable() throws Exception {
        given(createReservationUseCase.create(any()))
                .willThrow(new PaymentServiceUnavailableException("down", new RuntimeException("timeout")));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditCardRequestJson("\"OK-123\"")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("PAYMENT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("https://marvel-hospitality/problems/PAYMENT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void returns400WhenCreditCardHasNoPaymentReference() throws Exception {
        for (String reference : List.of("null", "\"   \"")) {
            mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(creditCardRequestJson(reference)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("paymentReference"));
        }
        verifyNoInteractions(createReservationUseCase);
    }

    @Test
    void returns400ForUnknownEnumValue() throws Exception {
        String badEnumJson = validCashRequestJson().replace("\"MEDIUM\"", "\"HUGE\"");

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badEnumJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("roomSegment"));
    }

    @Test
    void getReturns404ForReservationOfAnotherProperty() throws Exception {
        given(getReservationUseCase.get("AMS01", "P4145478"))
                .willThrow(new ReservationNotFoundException("AMS01", "P4145478"));

        mvc.perform(get("/properties/AMS01/reservations/P4145478").with(readJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void returns404ForUnknownProperty() throws Exception {
        given(createReservationUseCase.create(any())).willThrow(new PropertyNotFoundException("AMS01"));

        mvc.perform(post("/properties/AMS01/reservations").with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCashRequestJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPERTY_NOT_FOUND"));
    }
}
