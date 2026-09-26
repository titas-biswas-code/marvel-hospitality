package com.marvel.hospitality.platform.outbox;

import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends one row to {@code outbox_event} (docs/contracts/outbox-and-inbox.md) inside the caller's own transaction
 * (ADR-0006): a service calls {@link #append} from within its own {@code @Transactional} use case, in the same
 * transaction as the state change the event describes, and Debezium's Outbox Event Router (ADR-0007) publishes the
 * row straight off the write-ahead log — the application itself never calls {@code KafkaTemplate} for a domain
 * event (ADR-0006).
 *
 * <p>Deliberately built on {@link JdbcClient}, not a JPA entity: the application never reads {@code outbox_event}
 * back (Debezium's logical replication is the only reader), so mapping it as an entity would add entity scanning
 * and a repository nobody calls, purely to satisfy a starter. {@code JdbcClient} still joins the surrounding
 * {@code JpaTransactionManager}'s transaction: both bind to the same {@code DataSource} connection tracked by
 * {@link TransactionSynchronizationManager}, so the insert commits or rolls back with everything else in the
 * transaction — no second resource, no XA, no extra round trip.
 */
public class OutboxEventWriter {

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String producer;

    public OutboxEventWriter(JdbcClient jdbcClient, JsonMapper jsonMapper, Clock clock, String producer) {
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.producer = producer;
    }

    /**
     * Inserts one {@code outbox_event} row and returns its {@code id}.
     *
     * @throws IllegalStateException if there is no active transaction — writing the outbox row on its own
     *         transaction could let it commit even though the state change it describes rolls back, or the other
     *         way around, which defeats the whole point of the pattern (ADR-0006)
     */
    public UUID append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxEventWriter.append() must run inside the caller's own transaction (ADR-0006); "
                            + "no active transaction for aggregate "
                            + message.aggregateType() + "/" + message.aggregateId());
        }
        UUID id = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(message.payload());
        jdbcClient.sql("""
                        INSERT INTO outbox_event
                            (id, aggregate_type, aggregate_id, event_type, event_version, topic, property_id,
                             producer, traceparent, payload, created_at)
                        VALUES
                            (:id, :aggregateType, :aggregateId, :eventType, :eventVersion, :topic, :propertyId,
                             :producer, :traceparent, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("id", id)
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("eventType", message.eventType())
                .param("eventVersion", message.eventVersion())
                .param("topic", message.topic())
                .param("propertyId", message.propertyId(), Types.VARCHAR)
                .param("producer", producer)
                // Trace context propagation (outbox -> Kafka headers -> consumer) lands in PR-10 (observability).
                .param("traceparent", null, Types.VARCHAR)
                .param("payload", payload)
                .param("createdAt", OffsetDateTime.now(clock))
                .update();
        return id;
    }
}
