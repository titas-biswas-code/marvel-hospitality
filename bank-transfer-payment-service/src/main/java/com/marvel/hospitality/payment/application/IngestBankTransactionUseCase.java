package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.BankTransaction;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank webhook's use case (rest-api.md, ADR-0014): one local transaction that stores the transaction in the
 * ledger and writes its {@code PaymentReceived} outbox row, or neither (ADR-0006 step 2).
 *
 * <p>Idempotent on {@code bankTransactionRef}: the ledger insert is {@code INSERT ... ON CONFLICT DO NOTHING}, so a
 * repeat (a bank retry, or two concurrent deliveries of the same transaction) stores nothing, writes no second
 * outbox row, and answers with the transaction already in the ledger. Relying on the unique constraint rather than
 * a look-up-first keeps this correct under concurrency; catching a constraint violation instead would abort the
 * Postgres transaction and need a second one to reload.
 */
@Service
public class IngestBankTransactionUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestBankTransactionUseCase.class);

    private final BankTransactionLedger ledger;
    private final PaymentEventOutbox outbox;
    private final Clock clock;

    public IngestBankTransactionUseCase(BankTransactionLedger ledger, PaymentEventOutbox outbox, Clock clock) {
        this.ledger = ledger;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public IngestResult ingest(IngestBankTransactionCommand command) {
        BankTransaction received = BankTransaction.receive(
                command.bankTransactionRef(), command.debtorAccountNumber(), command.debtorName(), command.amount(),
                command.currency(), command.remittanceInformation(), command.bookedAt(), clock.instant());

        if (ledger.addIfAbsent(received, command.raw())) {
            outbox.paymentReceived(received);
            log.info("Ingested bank transaction {} as payment {}", received.bankTransactionRef(), received.paymentId());
            return new IngestResult(received, true);
        }

        BankTransaction existing = ledger.findByBankTransactionRef(received.bankTransactionRef())
                .orElseThrow(() -> new IllegalStateException(
                        "bankTransactionRef " + received.bankTransactionRef() + " conflicted but is not in the ledger"));
        if (existing.describesSameTransferAs(received)) {
            log.debug("Duplicate bank transaction {} (payment {}); nothing stored",
                    existing.bankTransactionRef(), existing.paymentId());
        } else {
            // The contract says a repeated reference returns the existing payment; a different body under the same
            // reference is a bank-side error worth a human's attention, but not a reason to book money twice.
            log.warn("Bank transaction {} repeated with different details; kept the original payment {}",
                    existing.bankTransactionRef(), existing.paymentId());
        }
        return new IngestResult(existing, false);
    }
}
