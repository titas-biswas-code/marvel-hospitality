package com.marvel.hospitality.payment.domain;

/**
 * Lifecycle of a {@link RefundInstruction} (matches the {@code refund_instruction.status} CHECK constraint,
 * persisted as {@code varchar}, never {@code ORDINAL}). {@code RECEIVED} exists even though this service's stub
 * executor runs synchronously: a real payout rail would commit {@code RECEIVED} first, call the rail outside any
 * transaction, and record {@code EXECUTED}/{@code FAILED} in a second transaction ({@link
 * com.marvel.hospitality.payment.application.RefundExecutor}).
 */
public enum RefundInstructionStatus {
    RECEIVED,
    EXECUTED,
    FAILED
}
