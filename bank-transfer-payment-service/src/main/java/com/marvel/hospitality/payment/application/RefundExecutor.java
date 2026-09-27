package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.RefundInstruction;

/**
 * The payout rail: transfers {@code instruction.amount()} to {@code instruction.creditorAccountNumber()}. This
 * service's only implementation is a local stub ({@code infrastructure.payout.StubRefundExecutor}), so {@link
 * ExecuteRefundUseCase} is allowed to call it from inside the consumer's own database transaction and persist the
 * outcome in the same transaction as the {@code RECEIVED} instruction.
 *
 * <p>A real (remote) rail must never be called with a database transaction open: committing {@code RECEIVED} first,
 * calling the rail outside any transaction, and recording {@code EXECUTED}/{@code FAILED} in a second transaction is
 * exactly why {@link com.marvel.hospitality.payment.domain.RefundInstructionStatus#RECEIVED} exists as a distinct,
 * persisted state rather than being skipped.
 */
public interface RefundExecutor {

    RefundExecution execute(RefundInstruction instruction);
}
