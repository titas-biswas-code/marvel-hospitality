package com.marvel.hospitality.payment.infrastructure.persistence;

import com.marvel.hospitality.payment.application.RefundInstructionRepository;
import com.marvel.hospitality.payment.domain.RefundInstruction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class JpaRefundInstructionRepository implements RefundInstructionRepository {

    private final RefundInstructionJpaRepository repository;

    JpaRefundInstructionRepository(RefundInstructionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void add(RefundInstruction instruction) {
        repository.insert(instruction.refundId(), instruction.paymentId(), instruction.reservationId(),
                instruction.propertyId(), instruction.creditorAccountNumber(), instruction.amount(),
                instruction.currency(), instruction.reason().name(), instruction.status().name(),
                instruction.failureReason(), instruction.createdAt(), instruction.executedAt());
    }

    @Override
    public Optional<RefundInstruction> findById(UUID refundId) {
        return repository.findById(refundId).map(RefundInstructionEntity::toDomain);
    }
}
