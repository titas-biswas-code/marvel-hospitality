package com.marvel.hospitality.payment.infrastructure.payout;

import com.marvel.hospitality.payment.application.RefundExecution;
import com.marvel.hospitality.payment.application.RefundExecutor;
import com.marvel.hospitality.payment.domain.RefundInstruction;
import org.springframework.stereotype.Component;

/**
 * The only payout rail this assignment has (ADR-0014): deterministic and local, so {@link
 * com.marvel.hospitality.payment.application.ExecuteRefundUseCase} may call it inside the consumer's own database
 * transaction (see {@link RefundExecutor}'s javadoc for why a real rail could not).
 */
@Component
class StubRefundExecutor implements RefundExecutor {

    static final String CREDITOR_ACCOUNT_REJECTED = "CREDITOR_ACCOUNT_REJECTED";

    @Override
    public RefundExecution execute(RefundInstruction instruction) {
        if (instruction.creditorAccountNumber().startsWith("FAIL")) {
            return RefundExecution.failure(CREDITOR_ACCOUNT_REJECTED);
        }
        return RefundExecution.success();
    }
}
