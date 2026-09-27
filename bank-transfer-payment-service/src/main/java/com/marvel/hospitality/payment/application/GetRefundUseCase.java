package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.RefundInstruction;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetRefundUseCase {

    private final RefundInstructionRepository refundInstructions;

    public GetRefundUseCase(RefundInstructionRepository refundInstructions) {
        this.refundInstructions = refundInstructions;
    }

    @Transactional(readOnly = true)
    public RefundInstruction get(UUID refundId) {
        return refundInstructions.findById(refundId).orElseThrow(() -> new RefundNotFoundException(refundId));
    }
}
