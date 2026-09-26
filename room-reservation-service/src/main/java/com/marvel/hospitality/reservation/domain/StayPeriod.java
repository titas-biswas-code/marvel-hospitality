package com.marvel.hospitality.reservation.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A stay's check-in/check-out dates, half-open like the DB's {@code daterange(start, end, '[)')}
 * (ADR-0005): a check-out date equals the next reservation's check-in date without the two overlapping.
 *
 * <p>The compact constructor only validates shape — end after start, at most {@link #MAX_NIGHTS} nights —
 * and deliberately never checks "is this in the past". Rehydrating an old reservation from the DB must
 * always succeed even though its start date is long past. "Not in the past" is a *new booking* rule only,
 * applied by {@link #forNewBooking(LocalDate, LocalDate, LocalDate)}.
 */
public record StayPeriod(LocalDate startDate, LocalDate endDate) {

    public static final int MAX_NIGHTS = 30;

    public StayPeriod {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        if (!endDate.isAfter(startDate)) {
            throw new InvalidStayException("endDate", "endDate must be after startDate");
        }
        long nights = ChronoUnit.DAYS.between(startDate, endDate);
        if (nights > MAX_NIGHTS) {
            throw new InvalidStayException("endDate", "stay must not exceed " + MAX_NIGHTS + " nights");
        }
    }

    /**
     * @param todayAtProperty "today" in the property's own timezone ({@link Property#today}), not the
     *                        server's — a stay starting "yesterday" locally must be rejected even if it is
     *                        still today in UTC (or vice versa)
     */
    public static StayPeriod forNewBooking(LocalDate startDate, LocalDate endDate, @Nullable LocalDate todayAtProperty) {
        if (startDate != null && todayAtProperty != null && startDate.isBefore(todayAtProperty)) {
            throw new InvalidStayException("startDate", "startDate must not be before today");
        }
        return new StayPeriod(startDate, endDate);
    }

    public int nights() {
        return (int) ChronoUnit.DAYS.between(startDate, endDate);
    }
}
