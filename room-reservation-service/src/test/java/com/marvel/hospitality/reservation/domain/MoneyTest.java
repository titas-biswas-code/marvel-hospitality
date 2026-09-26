package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesToTwoDecimals() {
        assertThat(Money.eur("120").amount()).isEqualByComparingTo("120.00");
        assertThat(Money.eur(new BigDecimal("120")).amount()).isEqualByComparingTo("120.00");
    }

    @Test
    void rejectsMoreThanTwoFractionDigits() {
        assertThatThrownBy(() -> Money.eur("120.005")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonEurCurrency() {
        assertThatThrownBy(() -> new Money(new BigDecimal("10.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.eur("-1.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipliesByNights() {
        assertThat(Money.eur("120.00").times(2)).isEqualTo(Money.eur("240.00"));
    }
}
