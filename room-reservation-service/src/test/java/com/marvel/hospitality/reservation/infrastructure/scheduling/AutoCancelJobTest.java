package com.marvel.hospitality.reservation.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.reservation.application.CancelOverdueReservationUseCase;
import com.marvel.hospitality.reservation.application.CancelledReservation;
import com.marvel.hospitality.reservation.application.OverdueCandidate;
import com.marvel.hospitality.reservation.application.OverduePaymentReservationQuery;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AutoCancelJob}'s own logic — paging, tallying, metrics — against a fake
 * {@link OverduePaymentReservationQuery} and a scripted {@link CancelOverdueReservationUseCase}. Nothing here talks
 * to a database: claiming, locking and transactions belong to {@code CancelOverdueReservationUseCase} and its own
 * persistence-integration coverage, not to this job.
 */
class AutoCancelJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant DEADLINE = Instant.parse("2026-09-25T00:00:00Z");
    private static final String PROPERTY_ID = "AMS01";

    @Test
    void cancelsEveryDueRowAcrossSeveralPages() {
        List<OverdueCandidate> rows = candidates(5);
        FakeOverduePaymentReservationQuery query = new FakeOverduePaymentReservationQuery(rows);
        ScriptedCancelOverdueReservationUseCase useCase = ScriptedCancelOverdueReservationUseCase.cancellingEverything(PROPERTY_ID);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoCancelJob job = new AutoCancelJob(query, useCase, registry, CLOCK, 2);

        AutoCancelRun run = job.run();

        assertThat(run).isEqualTo(new AutoCancelRun(5, 0, 0));
        assertThat(useCase.calls).hasSize(5);
        assertThat(registry.get("reservation.autocancel.cancelled").tag("propertyId", PROPERTY_ID).counter().count())
                .isEqualTo(5.0);
        // Pages of 2, 2, 1: the third page is already short of the batch size, so no fourth (empty) call is made.
        assertThat(query.pageCalls).isEqualTo(3);
    }

    @Test
    void aFailingRowIsCountedAndTheRunContinues() {
        List<OverdueCandidate> rows = candidates(3);
        FakeOverduePaymentReservationQuery query = new FakeOverduePaymentReservationQuery(rows);
        ScriptedCancelOverdueReservationUseCase useCase = ScriptedCancelOverdueReservationUseCase.cancellingEverything(PROPERTY_ID);
        useCase.failing(rows.get(1).id());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoCancelJob job = new AutoCancelJob(query, useCase, registry, CLOCK, 10);

        AutoCancelRun run = job.run();

        assertThat(run).isEqualTo(new AutoCancelRun(2, 0, 1));
        // Every row was still attempted, including the two after the failing one.
        assertThat(useCase.calls).containsExactly(rows.get(0).id(), rows.get(1).id(), rows.get(2).id());
    }

    @Test
    void aRowThatAlwaysFailsDoesNotLoopForever() {
        List<OverdueCandidate> rows = candidates(1);
        FakeOverduePaymentReservationQuery query = new FakeOverduePaymentReservationQuery(rows);
        ScriptedCancelOverdueReservationUseCase useCase = ScriptedCancelOverdueReservationUseCase.cancellingEverything(PROPERTY_ID);
        useCase.failing(rows.get(0).id());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoCancelJob job = new AutoCancelJob(query, useCase, registry, CLOCK, 10);

        AutoCancelRun run = job.run();

        assertThat(run).isEqualTo(new AutoCancelRun(0, 0, 1));
        // The cursor advances past the failing row even though it threw, so the loop does not spin on it: one page,
        // short of the batch size, ends the run.
        assertThat(query.pageCalls).isEqualTo(1);
    }

    @Test
    void aRowClaimedElsewhereCountsAsSkipped() {
        List<OverdueCandidate> rows = candidates(1);
        FakeOverduePaymentReservationQuery query = new FakeOverduePaymentReservationQuery(rows);
        ScriptedCancelOverdueReservationUseCase useCase = ScriptedCancelOverdueReservationUseCase.cancellingEverything(PROPERTY_ID);
        useCase.skipping(rows.get(0).id());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoCancelJob job = new AutoCancelJob(query, useCase, registry, CLOCK, 10);

        AutoCancelRun run = job.run();

        assertThat(run).isEqualTo(new AutoCancelRun(0, 1, 0));
        assertThat(registry.find("reservation.autocancel.cancelled").counter()).isNull();
    }

    @Test
    void overdueGaugeShowsRowsStillDueAfterTheRun() {
        List<OverdueCandidate> rows = candidates(2);
        FakeOverduePaymentReservationQuery query = new FakeOverduePaymentReservationQuery(rows);
        query.due = 4; // e.g. more rows became due, or were skipped, while this run was in flight
        ScriptedCancelOverdueReservationUseCase useCase = ScriptedCancelOverdueReservationUseCase.cancellingEverything(PROPERTY_ID);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoCancelJob job = new AutoCancelJob(query, useCase, registry, CLOCK, 10);

        job.run();

        assertThat(registry.get("reservation.autocancel.overdue").gauge().value()).isEqualTo(4.0);
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        FakeOverduePaymentReservationQuery query = new FakeOverduePaymentReservationQuery(List.of());
        ScriptedCancelOverdueReservationUseCase useCase = ScriptedCancelOverdueReservationUseCase.cancellingEverything(PROPERTY_ID);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> new AutoCancelJob(query, useCase, registry, CLOCK, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    private static List<OverdueCandidate> candidates(int count) {
        List<OverdueCandidate> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new OverdueCandidate(UUID.randomUUID(), DEADLINE));
        }
        return rows;
    }

    /**
     * The unlocked page reader, faked: serves {@code all} in order, paging forward from the cursor exactly as the
     * real query is contracted to (ADR-0010). {@link #claimIfDue} is never meant to be called by {@link AutoCancelJob}
     * itself — only {@link CancelOverdueReservationUseCase} calls it — so it throws if it ever is.
     */
    private static final class FakeOverduePaymentReservationQuery implements OverduePaymentReservationQuery {

        private final List<OverdueCandidate> all;
        int pageCalls;
        long due;

        FakeOverduePaymentReservationQuery(List<OverdueCandidate> all) {
            this.all = all;
            this.due = all.size();
        }

        @Override
        public List<OverdueCandidate> findDue(Instant now, @Nullable OverdueCandidate after, int limit) {
            pageCalls++;
            int start = after == null ? 0 : indexOf(after.id()) + 1;
            if (start >= all.size()) {
                return List.of();
            }
            return List.copyOf(all.subList(start, Math.min(all.size(), start + limit)));
        }

        @Override
        public Optional<Reservation> claimIfDue(UUID id, Instant now) {
            throw new UnsupportedOperationException("AutoCancelJob must go through CancelOverdueReservationUseCase");
        }

        @Override
        public long countDue(Instant now) {
            return due;
        }

        private int indexOf(UUID id) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(id)) {
                    return i;
                }
            }
            throw new IllegalStateException("Cursor id not found among candidates: " + id);
        }
    }

    /**
     * A {@link CancelOverdueReservationUseCase} whose outcome per row is scripted rather than backed by a real
     * transaction or repository: {@link CancelOverdueReservationUseCase} is a plain concrete class (not an
     * interface), so the only Mockito-free way to stub it is to subclass it and override its one public method,
     * passing nulls to the superclass constructor for the collaborators this fake never touches.
     */
    private static final class ScriptedCancelOverdueReservationUseCase extends CancelOverdueReservationUseCase {

        private final String propertyId;
        private final Map<UUID, RuntimeException> failures = new HashMap<>();
        private final Map<UUID, Boolean> skips = new HashMap<>();
        final List<UUID> calls = new ArrayList<>();

        private ScriptedCancelOverdueReservationUseCase(String propertyId) {
            super(null, null, null, null, CLOCK);
            this.propertyId = propertyId;
        }

        static ScriptedCancelOverdueReservationUseCase cancellingEverything(String propertyId) {
            return new ScriptedCancelOverdueReservationUseCase(propertyId);
        }

        void failing(UUID id) {
            failures.put(id, new RuntimeException("simulated failure claiming/cancelling " + id));
        }

        void skipping(UUID id) {
            skips.put(id, true);
        }

        @Override
        public Optional<CancelledReservation> cancelIfOverdue(UUID id, Instant now) {
            calls.add(id);
            RuntimeException failure = failures.get(id);
            if (failure != null) {
                throw failure;
            }
            if (skips.getOrDefault(id, false)) {
                return Optional.empty();
            }
            return Optional.of(new CancelledReservation(ReservationId.of("P0000001"), propertyId));
        }
    }
}
