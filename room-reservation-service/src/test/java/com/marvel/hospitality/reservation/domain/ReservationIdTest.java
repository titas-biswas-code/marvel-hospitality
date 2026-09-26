package com.marvel.hospitality.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationIdTest {

    private final ReservationIdGenerator generator = new ReservationIdGenerator(new SecureRandom());

    @Test
    void generatesEightCharCrockfordIdsStartingWithP() {
        Set<String> generated = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> {
            ReservationId id = generator.next();
            assertThat(id.value()).hasSize(8).startsWith("P").matches("^P[0-9A-HJKMNP-TV-Z]{7}$");
            generated.add(id.value());
        });
        // Collisions are possible but astronomically unlikely across 10k draws from a ~34-billion id space.
        assertThat(generated).hasSizeGreaterThan(9_990);
    }

    @Test
    void neverContainsAmbiguousLetters() {
        IntStream.range(0, 10_000)
                .mapToObj(i -> generator.next().value())
                .forEach(value -> assertThat(value).doesNotContainPattern("[ILOU]"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "P414547", // 7 chars total, too short
                "P41454789", // too long
                "X4145478", // wrong prefix
                "P414547I", // contains I
                "P414547L", // contains L
                "P414547O", // contains O
                "P414547U", // contains U
                "p4145478", // lowercase
                ""
            })
    void rejectsMalformedIds(String candidate) {
        assertThatThrownBy(() -> ReservationId.of(candidate)).isInstanceOf(IllegalArgumentException.class);
    }
}
