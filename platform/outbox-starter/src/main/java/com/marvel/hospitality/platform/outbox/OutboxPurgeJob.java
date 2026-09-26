package com.marvel.hospitality.platform.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes {@code outbox_event} rows older than the retention (7 days by default), once a day
 * (docs/contracts/outbox-and-inbox.md, ADR-0007). Debezium only ever needs the WAL insert, so a row is dead weight
 * once it has been published; it is kept for a week purely for auditing and debugging.
 *
 * <p>Safe with several instances and without a lock: the {@code DELETE} is idempotent, so two instances purging at
 * the same moment just find nothing left for the second one. The deletes never reach Kafka: the connectors skip
 * delete operations ({@code skipped.operations} in {@code infra/debezium/*.json}), and the Outbox Event Router
 * would drop them anyway.
 *
 * <p>The cutoff comes from the injected {@link Clock}, not from the database's {@code now()}, so tests can pin it.
 */
public class OutboxPurgeJob {

    /** Every day at 03:30 UTC: quiet hours for a European hotel group. */
    public static final String DEFAULT_CRON = "0 30 3 * * *";

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final Duration retention;

    public OutboxPurgeJob(JdbcClient jdbcClient, Clock clock, Duration retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("marvel.outbox.purge.retention must be positive, was " + retention);
        }
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${marvel.outbox.purge.cron:" + DEFAULT_CRON + "}", zone = "UTC")
    void purgeOnSchedule() {
        purge();
    }

    /** Deletes every row created before {@code now - retention} and returns how many were deleted. */
    public int purge() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(retention);
        int deleted = jdbcClient.sql("DELETE FROM outbox_event WHERE created_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
        log.info("Purged {} outbox_event rows created before {}", deleted, cutoff);
        return deleted;
    }
}
