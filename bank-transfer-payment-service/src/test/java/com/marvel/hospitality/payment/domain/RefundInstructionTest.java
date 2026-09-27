package com.marvel.hospitality.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain-Java invariants and transitions of {@link RefundInstruction} (no Spring anywhere in this test, ADR-0001's domain rule). */
class RefundInstructionTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T09:16:00Z");
    private static final Instant EXECUTED_AT = Instant.parse("2026-10-01T09:16:05Z");
    private static final String IBAN = "NL91ABNA0417164300";

    private static RefundInstruction received() {
        return RefundInstruction.received(UUID.randomUUID(), UUID.randomUUID(), "P4145478", "AMS01", IBAN,
                new BigDecimal("30.00"), "EUR", RefundReason.OVERPAYMENT, CREATED_AT);
    }

    @Test
    void receivedInstructionStartsInReceivedStatusWithNoFailureOrExecutionTime() {
        RefundInstruction instruction = received();

        assertThat(instruction.status()).isEqualTo(RefundInstructionStatus.RECEIVED);
        assertThat(instruction.failureReason()).isNull();
        assertThat(instruction.executedAt()).isNull();
    }

    @Test
    void normalisesAWholeEuroAmountToScaleTwo() {
        RefundInstruction instruction = RefundInstruction.received(UUID.randomUUID(), UUID.randomUUID(), "P4145478",
                "AMS01", IBAN, new BigDecimal("30"), "EUR", RefundReason.OVERPAYMENT, CREATED_AT);

        assertThat(instruction.amount()).isEqualByComparingTo("30.00");
        assertThat(instruction.amount().scale()).isEqualTo(2);
    }

    @Test
    void executedTransitionSetsExecutedAtAndKeepsFailureReasonNull() {
        RefundInstruction executed = received().executed(EXECUTED_AT);

        assertThat(executed.status()).isEqualTo(RefundInstructionStatus.EXECUTED);
        assertThat(executed.executedAt()).isEqualTo(EXECUTED_AT);
        assertThat(executed.failureReason()).isNull();
    }

    @Test
    void failedTransitionSetsFailureReasonAndLeavesExecutedAtNull() {
        RefundInstruction failed = received().failed("CREDITOR_ACCOUNT_REJECTED");

        assertThat(failed.status()).isEqualTo(RefundInstructionStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("CREDITOR_ACCOUNT_REJECTED");
        assertThat(failed.executedAt()).isNull();
    }

    @Test
    void rejectsATransitionOutOfExecuted() {
        RefundInstruction executed = received().executed(EXECUTED_AT);

        assertThatThrownBy(() -> executed.failed("TOO_LATE"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executed.executed(EXECUTED_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsATransitionOutOfFailed() {
        RefundInstruction failed = received().failed("CREDITOR_ACCOUNT_REJECTED");

        assertThatThrownBy(() -> failed.executed(EXECUTED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.failed("AGAIN"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> RefundInstruction.received(UUID.randomUUID(), UUID.randomUUID(), "P4145478",
                "AMS01", IBAN, BigDecimal.ZERO, "EUR", RefundReason.OVERPAYMENT, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountWithMoreThanTwoFractionDigits() {
        assertThatThrownBy(() -> RefundInstruction.received(UUID.randomUUID(), UUID.randomUUID(), "P4145478",
                "AMS01", IBAN, new BigDecimal("1.005"), "EUR", RefundReason.OVERPAYMENT, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankReservationId() {
        assertThatThrownBy(() -> RefundInstruction.received(UUID.randomUUID(), UUID.randomUUID(), " ",
                "AMS01", IBAN, new BigDecimal("30.00"), "EUR", RefundReason.OVERPAYMENT, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
