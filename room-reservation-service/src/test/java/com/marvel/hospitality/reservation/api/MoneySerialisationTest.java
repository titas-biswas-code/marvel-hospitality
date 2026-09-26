package com.marvel.hospitality.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0016 / application.yml's {@code spring.jackson.write.write-bigdecimal-as-plain: true}: a {@link BigDecimal}
 * amount must always render as a plain decimal ({@code 240.00}), never in exponent form ({@code 2.4E+2}), because
 * money leaves the wire exactly as {@link com.marvel.hospitality.reservation.domain.Money} stores it (scale 2). Uses
 * the real {@link JsonMapper} bean so the actual application configuration is under test, the same way
 * {@code SecurityProblemHandler} (platform/security-starter) is given that bean, not a locally built one.
 */
@JsonTest
class MoneySerialisationTest {

    @Autowired
    private JsonMapper jsonMapper;

    private static ReservationResponse cashResponse(BigDecimal totalAmount, BigDecimal amountReceived) {
        return new ReservationResponse(
                "P4145478",
                "AMS01",
                ReservationStatus.CONFIRMED,
                "Ada Lovelace",
                "101",
                RoomSegment.MEDIUM,
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-12"),
                2,
                PaymentMode.CASH,
                null,
                totalAmount,
                amountReceived,
                "EUR",
                null,
                null,
                Instant.parse("2026-09-26T10:00:00Z"),
                Instant.parse("2026-09-26T10:00:00Z"));
    }

    @Test
    void serialisesMoneyAsPlainTwoDecimalNumber() {
        String json = jsonMapper.writeValueAsString(cashResponse(new BigDecimal("240.00"), new BigDecimal("0.00")));

        assertThat(json).contains("\"totalAmount\":240.00");
        assertThat(json).contains("\"amountReceived\":0.00");
        assertThat(json).doesNotContain("E+").doesNotContain("e+");
    }

    @Test
    void serialisesAValueConstructedFromExponentNotationAsPlainToo() {
        BigDecimal fromExponent = new BigDecimal("2.4E+2").setScale(2);

        String json = jsonMapper.writeValueAsString(cashResponse(fromExponent, new BigDecimal("0.00")));

        assertThat(json).contains("\"totalAmount\":240.00");
        assertThat(json).doesNotContain("E+").doesNotContain("e+");
    }
}
