package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Exercises the deadline-vs-now rejection through {@link BankTransferPaymentModeHandler}, which is the only
 * place {@link PaymentDeadlinePolicy}'s output is compared against "now" (rest-api.md: {@code paymentDeadlineAt
 * <= now} is rejected, so the boundary itself is not far enough in the future).
 */
class PaymentDeadlinePolicyTest {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    private final BankTransferPaymentModeHandler handler = new BankTransferPaymentModeHandler(new PaymentDeadlinePolicy());

    @Test
    void bankTransferIsRejectedWhenDeadlineEqualsNow() {
        // Deadline for 2026-10-10 is exactly 2026-10-07T22:00:00Z (Amsterdam local midnight, minus 2 days).
        Instant deadline = Instant.parse("2026-10-07T22:00:00Z");
        Clock clock = Clock.fixed(deadline, ZoneOffset.UTC);
        Property property = new Property("AMS01", "Marvel Amsterdam", AMSTERDAM, "NL00MARV0000000001");
        StayPeriod stay = new StayPeriod(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"));

        assertThatThrownBy(() -> handler.handle(new PaymentModeContext(property, stay, Money.eur("240.00"), Instant.now(clock))))
                .isInstanceOf(BankTransferLeadTimeTooShortException.class)
                .satisfies(e -> assertThat(((BankTransferLeadTimeTooShortException) e).deadline()).isEqualTo(deadline));
    }

    @Test
    void bankTransferIsRejectedWhenDeadlineIsPast() {
        Instant deadline = Instant.parse("2026-10-07T22:00:00Z");
        Clock clock = Clock.fixed(deadline.plusSeconds(1), ZoneOffset.UTC);
        Property property = new Property("AMS01", "Marvel Amsterdam", AMSTERDAM, "NL00MARV0000000001");
        StayPeriod stay = new StayPeriod(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"));

        assertThatThrownBy(() -> handler.handle(new PaymentModeContext(property, stay, Money.eur("240.00"), Instant.now(clock))))
                .isInstanceOf(BankTransferLeadTimeTooShortException.class);
    }

    @Test
    void bankTransferIsAcceptedOneSecondBeforeDeadline() {
        Instant deadline = Instant.parse("2026-10-07T22:00:00Z");
        Clock clock = Clock.fixed(deadline.minusSeconds(1), ZoneOffset.UTC);
        Property property = new Property("AMS01", "Marvel Amsterdam", AMSTERDAM, "NL00MARV0000000001");
        StayPeriod stay = new StayPeriod(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"));

        InitialOutcome outcome = handler.handle(new PaymentModeContext(property, stay, Money.eur("240.00"), Instant.now(clock)));

        assertThat(outcome.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(outcome.paymentDeadlineAt()).isEqualTo(deadline);
    }
}
