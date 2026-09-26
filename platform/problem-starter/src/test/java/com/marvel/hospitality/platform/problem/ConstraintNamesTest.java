package com.marvel.hospitality.platform.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintNamesTest {

    @Test
    void readsTheConstraintNameFromAWrappedPsqlServerErrorMessage() {
        // Field letters per the Postgres wire protocol (pgjdbc's ServerErrorMessage parses them):
        // S=Severity, C=SQLSTATE, M=Message, n=Constraint.
        ServerErrorMessage serverError =
                new ServerErrorMessage("SERROR\0C23P01\0Mconflicting key\0nreservation_no_overlap\0");
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("duplicate key", new PSQLException(serverError));

        assertThat(ConstraintNames.of(ex)).contains("reservation_no_overlap");
    }

    @Test
    void fallsBackToTheHibernateConstraintNameWhenNoPostgresDetailIsPresent() {
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "could not execute statement", new SQLException("failed"), "reservation_no_overlap");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicate key", hibernateException);

        assertThat(ConstraintNames.of(ex)).contains("reservation_no_overlap");
    }

    @Test
    void isEmptyWhenNoCauseInTheChainCarriesAConstraintName() {
        assertThat(ConstraintNames.of(new RuntimeException("boom"))).isEmpty();
        assertThat(ConstraintNames.of(null)).isEmpty();
    }

    @Test
    void sqlStateReadsTheFirstSqlExceptionInTheCauseChain() {
        SQLException sqlException = new SQLException("failed", "23P01");

        assertThat(ConstraintNames.sqlState(new RuntimeException("wrapped", sqlException))).contains("23P01");
        assertThat(ConstraintNames.sqlState(new RuntimeException("no sql exception"))).isEmpty();
    }
}
