package com.marvel.hospitality.reservation.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Mints candidate {@link ReservationId}s. Global uniqueness is enforced by the DB unique index on
 * {@code reservation.reservation_id} (identifiers.md) — this class only produces well-formed, unbiased
 * candidates from the Crockford alphabet; the application layer retries on a unique-constraint collision
 * (rare, but possible with an 8-char id space) up to a small maximum and then fails loudly.
 */
public final class ReservationIdGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int RANDOM_CHAR_COUNT = 7;

    private final RandomGenerator random;

    public ReservationIdGenerator(RandomGenerator random) {
        this.random = random;
    }

    public ReservationIdGenerator() {
        this(new SecureRandom());
    }

    public ReservationId next() {
        StringBuilder id = new StringBuilder(RANDOM_CHAR_COUNT + 1).append('P');
        for (int i = 0; i < RANDOM_CHAR_COUNT; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return ReservationId.of(id.toString());
    }
}
