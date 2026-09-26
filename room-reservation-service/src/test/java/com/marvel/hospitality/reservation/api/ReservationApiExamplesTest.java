package com.marvel.hospitality.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.PropertyCatalog;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentDeadlinePolicy;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Swagger examples ({@link ReservationApiExamples}, copies of the contract's examples) must describe a booking the
 * seeded service would actually accept and the response it would actually give: an existing room of the right
 * segment, the seeded nightly rate times the nights, the deadline the policy computes. They once booked room 101 as
 * {@code MEDIUM} while the seed has 101 as {@code SMALL}, so "Try it out" answered 422; this test keeps that from
 * coming back. It does not check that the dates are still in the future, so the build never fails just because time
 * passed.
 *
 * <p>Same configuration as {@code ApplicationContextLoadsTest}, so it reuses that cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class ReservationApiExamplesTest {

    /** The property every example is booked in (the responses' {@code propertyId}). */
    private static final String PROPERTY_ID = "AMS01";

    @Autowired
    PropertyCatalog catalog;

    @Autowired
    JsonMapper jsonMapper;

    @ParameterizedTest
    @CsvSource({
            "BANK_TRANSFER, CREATE_BANK_TRANSFER_REQUEST, BANK_TRANSFER_RESPONSE",
            "CASH,          CREATE_CASH_REQUEST,          CASH_RESPONSE",
            "CREDIT_CARD,   CREATE_CREDIT_CARD_REQUEST,   CREDIT_CARD_RESPONSE"})
    void exampleBooksASeededRoomAndShowsTheResponseTheServiceWouldGive(String mode, String requestName,
            String responseName) throws Exception {
        JsonNode request = example(requestName);
        JsonNode response = example(responseName);
        Property property = catalog.findProperty(PROPERTY_ID).orElseThrow();
        Room room = catalog.findRoom(PROPERTY_ID, request.get("roomNumber").asString()).orElseThrow(
                () -> new AssertionError(requestName + " books a room the seed does not have"));
        RoomSegment segment = RoomSegment.valueOf(request.get("roomSegment").asString());
        LocalDate start = LocalDate.parse(request.get("startDate").asString());
        LocalDate end = LocalDate.parse(request.get("endDate").asString());
        long nights = ChronoUnit.DAYS.between(start, end);
        Money rate = catalog.findNightlyRate(PROPERTY_ID, segment).orElseThrow();

        assertThat(request.get("paymentMode").asString()).isEqualTo(mode);
        assertThat(segment).as("%s: room %s's seeded segment", requestName, room.roomNumber()).isEqualTo(room.segment());

        assertThat(response.get("propertyId").asString()).isEqualTo(PROPERTY_ID);
        assertThat(response.get("paymentMode").asString()).isEqualTo(mode);
        for (String field : new String[] {"customerName", "roomNumber", "roomSegment", "startDate", "endDate"}) {
            assertThat(response.get(field)).as("%s.%s", responseName, field).isEqualTo(request.get(field));
        }
        assertThat(response.get("nights").asLong()).isEqualTo(nights);
        assertThat(response.get("totalAmount").decimalValue())
                .isEqualByComparingTo(rate.amount().multiply(BigDecimal.valueOf(nights)));

        if (mode.equals("BANK_TRANSFER")) {
            Instant deadline = new PaymentDeadlinePolicy().deadlineFor(start, property.timezone());
            assertThat(Instant.parse(response.get("paymentDeadlineAt").asString())).isEqualTo(deadline);
            assertThat(response.get("bankTransferInstructions").asString())
                    .contains(response.get("totalAmount").decimalValue().toPlainString())
                    .contains(property.bankAccountNumber())
                    .contains(response.get("reservationId").asString());
        } else {
            assertThat(response.get("paymentDeadlineAt").isNull()).isTrue();
        }
    }

    private JsonNode example(String constantName) throws Exception {
        return jsonMapper.readTree((String) ReservationApiExamples.class.getDeclaredField(constantName).get(null));
    }
}
