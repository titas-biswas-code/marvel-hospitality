package com.marvel.hospitality.reservation.infrastructure.scheduling;

/** The tally of one {@link AutoCancelJob#run()} pass over the currently-due rows. */
public record AutoCancelRun(int cancelled, int skipped, int failed) {
}
