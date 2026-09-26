package com.marvel.hospitality.platform.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    @Test
    void ofSetsTypeTitleAndCodeFromTheGivenStatusAndCode() {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, "ROOM_UNAVAILABLE", "The room is already booked.");

        assertThat(problem.getType().toString()).isEqualTo(Problems.TYPE_PREFIX + "ROOM_UNAVAILABLE");
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo("The room is already booked.");
        assertThat(problem.getProperties()).containsEntry("code", "ROOM_UNAVAILABLE");
    }

    @Test
    void validationFailedIsA400WithTheGivenErrorsList() {
        List<InvalidField> errors = List.of(new InvalidField("name", "must not be blank"));

        ProblemDetail problem = Problems.validationFailed("Validation failed.", errors);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getType().toString()).isEqualTo(Problems.TYPE_PREFIX + Problems.VALIDATION_FAILED);
        assertThat(problem.getProperties()).containsEntry("code", Problems.VALIDATION_FAILED);
        assertThat(problem.getProperties()).containsEntry("errors", errors);
    }
}
