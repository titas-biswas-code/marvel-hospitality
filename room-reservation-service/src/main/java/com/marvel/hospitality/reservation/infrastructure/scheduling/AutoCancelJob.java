package com.marvel.hospitality.reservation.infrastructure.scheduling;

import com.marvel.hospitality.reservation.application.CancelOverdueReservationUseCase;
import com.marvel.hospitality.reservation.application.CancelledReservation;
import com.marvel.hospitality.reservation.application.OverdueCandidate;
import com.marvel.hospitality.reservation.application.OverduePaymentReservationQuery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives ADR-0010's auto-cancel scheduler: repeatedly reads a page of overdue bank-transfer reservations, without
 * taking any lock ({@link OverduePaymentReservationQuery#findDue}'s javadoc explains why a locked batch read would
 * self-deadlock against the per-row {@code REQUIRES_NEW} transaction), and hands each candidate to
 * {@link CancelOverdueReservationUseCase}, which claims and locks the row itself in its own transaction.
 *
 * <p><b>Restart safety:</b> nothing about a due row lives in this job's memory — the deadline is a persisted column
 * — so a restarted instance simply starts a fresh run and finds the same rows still due, nothing was "scheduled" to
 * lose. <b>Multi-instance safety:</b> two instances running this job at the same moment do not coordinate at all;
 * each row's {@code FOR UPDATE SKIP LOCKED} claim means at most one of them cancels it, and the other instance's
 * attempt on the very same row simply comes back as skipped. <b>Timing:</b> the due predicate itself guarantees a
 * row is never cancelled before its deadline; the polling interval only bounds how late after the deadline that
 * happens — within one {@code fixedDelay} interval, never immediately.
 *
 * <p>Within a single run, the keyset cursor advances past a row once it has been attempted, whatever the outcome —
 * cancelled, skipped, or thrown. A row that keeps throwing therefore cannot make this loop spin on it forever: it is
 * simply left for the next scheduled run to try again from scratch.
 */
public class AutoCancelJob {

    private static final Logger log = LoggerFactory.getLogger(AutoCancelJob.class);

    private final OverduePaymentReservationQuery overdue;
    private final CancelOverdueReservationUseCase cancelOverdue;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int batchSize;
    private final AtomicLong overdueGauge = new AtomicLong();

    public AutoCancelJob(OverduePaymentReservationQuery overdue, CancelOverdueReservationUseCase cancelOverdue,
            MeterRegistry meterRegistry, Clock clock, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        this.overdue = overdue;
        this.cancelOverdue = cancelOverdue;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.batchSize = batchSize;
        Gauge.builder("reservation.autocancel.overdue", overdueGauge, AtomicLong::get)
                .description("Overdue bank-transfer reservations still PENDING_PAYMENT after the last run")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${reservation.auto-cancel.interval:PT60S}",
            initialDelayString = "${reservation.auto-cancel.initial-delay:PT30S}")
    void runOnSchedule() {
        run();
    }

    /**
     * One full pass over every currently-due row, paging forward until a page comes back empty or short of
     * {@code batchSize} rows (i.e. exhausted). Never throws: a row that fails is counted and logged, not
     * propagated, so one bad row cannot abort the rest of the run — {@link #runOnSchedule} relies on that to keep
     * ticking on its own schedule.
     */
    public AutoCancelRun run() {
        Instant now = Instant.now(clock);
        @Nullable OverdueCandidate cursor = null;
        int cancelled = 0;
        int skipped = 0;
        int failed = 0;
        while (true) {
            List<OverdueCandidate> page = overdue.findDue(now, cursor, batchSize);
            if (page.isEmpty()) {
                break;
            }
            for (OverdueCandidate candidate : page) {
                try {
                    Optional<CancelledReservation> result = cancelOverdue.cancelIfOverdue(candidate.id(), now);
                    if (result.isPresent()) {
                        cancelled++;
                        Counter.builder("reservation.autocancel.cancelled")
                                .tag("propertyId", result.get().propertyId())
                                .register(meterRegistry)
                                .increment();
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("Auto-cancel of reservation row {} failed; retried on the next run", candidate.id(), ex);
                }
            }
            cursor = page.get(page.size() - 1);
            if (page.size() < batchSize) {
                break;
            }
        }
        overdueGauge.set(overdue.countDue(now));
        if (cancelled + skipped + failed > 0) {
            log.info("Auto-cancel run: {} cancelled, {} skipped, {} failed", cancelled, skipped, failed);
        } else {
            log.debug("Auto-cancel run: nothing due");
        }
        return new AutoCancelRun(cancelled, skipped, failed);
    }
}
