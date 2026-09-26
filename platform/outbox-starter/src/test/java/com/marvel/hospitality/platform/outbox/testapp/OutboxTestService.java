package com.marvel.hospitality.platform.outbox.testapp;

import com.marvel.hospitality.platform.outbox.OutboxEventWriter;
import com.marvel.hospitality.platform.outbox.OutboxMessage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stands in for a real use case: writes the outbox row as part of a larger business transaction. */
@Service
public class OutboxTestService {

    private final OutboxEventWriter writer;

    public OutboxTestService(OutboxEventWriter writer) {
        this.writer = writer;
    }

    @Transactional
    public UUID appendAndCommit(OutboxMessage message) {
        return writer.append(message);
    }

    /** Writes the outbox row, then fails the rest of the "business" work — the whole transaction must roll back. */
    @Transactional
    public void appendThenFailTheRestOfTheTransaction(OutboxMessage message) {
        writer.append(message);
        throw new IllegalStateException("simulated failure after the outbox write");
    }
}
