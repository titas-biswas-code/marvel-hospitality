package com.marvel.hospitality.platform.problem;

import java.sql.SQLException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Reads the database constraint name off an exception chain, so a service can map its own known constraints
 * (e.g. the overbooking exclusion constraint, ADR-0005) by name instead of blanket-mapping every
 * {@code DataIntegrityViolationException} to one status code (ADR-0005).
 *
 * <p>Postgres and Hibernate are both {@code compileOnly} to this starter, so a service without a database (e.g.
 * credit-card-payment-service) still gets {@link FallbackProblemAdvice} without pulling either in; the lookups
 * below are guarded by {@link ClassUtils#isPresent}.
 */
public final class ConstraintNames {

    private static final boolean POSTGRESQL_PRESENT =
            ClassUtils.isPresent("org.postgresql.util.PSQLException", ConstraintNames.class.getClassLoader());

    private static final boolean HIBERNATE_PRESENT = ClassUtils.isPresent(
            "org.hibernate.exception.ConstraintViolationException", ConstraintNames.class.getClassLoader());

    private ConstraintNames() {
    }

    /**
     * Walks the whole cause chain of {@code ex} for a constraint name, preferring the Postgres wire-protocol detail
     * (present whenever the JDBC driver is pgjdbc) and falling back to Hibernate's own translation.
     */
    public static Optional<String> of(@Nullable Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            Throwable current = cause;
            Optional<String> name = fromPostgresql(current).or(() -> fromHibernate(current));
            if (name.isPresent()) {
                return name;
            }
        }
        return Optional.empty();
    }

    /** Walks the whole cause chain of {@code ex} for the first {@link SQLException#getSQLState()}. */
    public static Optional<String> sqlState(@Nullable Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return Optional.of(sqlException.getSQLState());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> fromPostgresql(Throwable cause) {
        if (POSTGRESQL_PRESENT && cause instanceof org.postgresql.util.PSQLException psqlException) {
            org.postgresql.util.ServerErrorMessage message = psqlException.getServerErrorMessage();
            if (message != null && message.getConstraint() != null) {
                return Optional.of(message.getConstraint());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> fromHibernate(Throwable cause) {
        if (HIBERNATE_PRESENT
                && cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
                && constraintViolation.getConstraintName() != null) {
            return Optional.of(constraintViolation.getConstraintName());
        }
        return Optional.empty();
    }
}
