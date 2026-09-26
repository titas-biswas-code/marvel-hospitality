package com.marvel.hospitality.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} tests move by hand instead of sleeping (ADR-0010: "tests move time instead of sleeping"). Always
 * UTC, like {@link com.marvel.hospitality.reservation.config.ClockConfiguration}'s production bean; property-local
 * dates are still derived from each property's own {@code timezone}, never from this clock's zone.
 *
 * <p>Backed by an {@link AtomicReference}, so it is safe to read from a scheduler thread (the job under test) while
 * a test thread calls {@link #set} between phases, and safe for the concurrency test's two worker threads to read
 * {@link #instant()} at the same time the main thread does.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant initial) {
        this.now = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /** Moves the clock to exactly {@code instant}. */
    public void set(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant"));
    }

    /** Moves the clock forward (or backward, for a negative duration) by {@code amount}. */
    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(Objects.requireNonNull(amount, "amount")));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // Every caller in this service asks for the property's zone via Property#today(Clock), which calls
        // withZone; the returned clock must keep reading the same mutable instant, just rendered in that zone,
        // not freeze it the way Clock.fixed(now.get(), zone) would.
        return new ZoneView(this, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    /** A read-only view of the owning {@link MutableClock} in a different zone; still reads the live instant. */
    private static final class ZoneView extends Clock {
        private final MutableClock source;
        private final ZoneId zone;

        ZoneView(MutableClock source, ZoneId zone) {
            this.source = source;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new ZoneView(source, zone);
        }

        @Override
        public Instant instant() {
            return source.instant();
        }
    }
}
