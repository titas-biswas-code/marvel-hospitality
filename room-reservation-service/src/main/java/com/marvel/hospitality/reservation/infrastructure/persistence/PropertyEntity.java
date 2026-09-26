package com.marvel.hospitality.reservation.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA mapping of {@code property} (database-schemas.md). Rows are seeded by Flyway's repeatable migration, never
 * written by this service, so the mapping exists only to read reference data back into the domain {@code Property}
 * ({@link JpaPropertyCatalogAdapter}); {@code created_at} is marked read-only accordingly.
 */
@Entity
@Table(name = "property")
class PropertyEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @Column(name = "bank_account_number", nullable = false)
    private String bankAccountNumber;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PropertyEntity() {
        // JPA
    }

    String id() {
        return id;
    }

    String name() {
        return name;
    }

    String timezone() {
        return timezone;
    }

    String bankAccountNumber() {
        return bankAccountNumber;
    }
}
