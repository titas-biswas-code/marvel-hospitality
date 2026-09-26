package com.marvel.hospitality.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.application.PropertyNotFoundException;
import com.marvel.hospitality.reservation.application.ReceivedPaymentQueries;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.ReservationId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Web-layer slice test for the two reconciliation views (rest-api.md, ADR-0009), following the same
 * {@code @WebMvcTest} + {@code jwt()} conventions as {@code ReservationControllerTest}.
 */
@WebMvcTest(controllers = UnmatchedPaymentController.class)
@Import({ReservationProblemAdvice.class, MockJwtDecoderConfiguration.class})
class UnmatchedPaymentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReceivedPaymentQueries queries;

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor readJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("reservation:read"))
                .jwt(j -> j.claim("properties", List.of("AMS01")));
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor bankReadJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("bank:read"))
                .jwt(j -> j.claim("properties", List.of("AMS01")));
    }

    private static ReceivedPayment notPendingPayment() {
        return new ReceivedPayment(
                "6d1d2f5f-4e3b-5c7f-ad2f-1b2c3d4e5f6a",
                ReservationId.of("P4145478"),
                "AMS01",
                "NL91ABNA0417164300",
                Money.eur("240.00"),
                "1401541457 P4145478",
                "1401541457",
                PaymentMatchOutcome.UNMATCHED_NOT_PENDING,
                Instant.parse("2026-10-05T09:15:02Z"));
    }

    private static ReceivedPayment paymentWithoutReservation() {
        return new ReceivedPayment(
                "7e2e3f6f-5f4c-6d8f-be3f-2c3d4e5f6a7b",
                null,
                null,
                "NL91ABNA0417164300",
                Money.eur("50.00"),
                "thank you",
                null,
                PaymentMatchOutcome.UNMATCHED_FORMAT,
                Instant.parse("2026-10-06T09:15:02Z"));
    }

    @Test
    void listsNotPendingPaymentsOfProperty() throws Exception {
        given(queries.notPendingOfProperty("AMS01")).willReturn(List.of(notPendingPayment()));

        mvc.perform(get("/properties/AMS01/unmatched-payments").with(readJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value("6d1d2f5f-4e3b-5c7f-ad2f-1b2c3d4e5f6a"))
                .andExpect(jsonPath("$[0].reservationId").value("P4145478"))
                .andExpect(jsonPath("$[0].propertyId").value("AMS01"))
                .andExpect(jsonPath("$[0].outcome").value("UNMATCHED_NOT_PENDING"));
    }

    @Test
    void notPendingPaymentsOfUnknownPropertyIsNotFound() throws Exception {
        given(queries.notPendingOfProperty("AMS01")).willThrow(new PropertyNotFoundException("AMS01"));

        mvc.perform(get("/properties/AMS01/unmatched-payments").with(readJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPERTY_NOT_FOUND"));
    }

    @Test
    void listsPaymentsWithoutReservationForBankReaders() throws Exception {
        given(queries.withoutReservation()).willReturn(List.of(paymentWithoutReservation()));

        // bankReadJwt() carries properties: [AMS01], but this endpoint has no property check at all.
        MvcResult result = mvc.perform(get("/unmatched-payments").with(bankReadJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value("7e2e3f6f-5f4c-6d8f-be3f-2c3d4e5f6a7b"))
                .andExpect(jsonPath("$[0].outcome").value("UNMATCHED_FORMAT"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"reservationId\":null")
                .contains("\"propertyId\":null");
    }

    @Test
    void paymentsWithoutReservationRequireBankReadRole() throws Exception {
        mvc.perform(get("/unmatched-payments").with(readJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(queries);
    }

    @Test
    void unauthenticatedCallIsRejected() throws Exception {
        mvc.perform(get("/unmatched-payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
