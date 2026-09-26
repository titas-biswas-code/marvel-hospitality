package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.RoomSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping of {@code room_rate} (database-schemas.md): the nightly rate per property and {@link RoomSegment}.
 * Data, not an enum-backed constant, because rates vary per property and change often (ADR-0004); read-only from
 * this service, like {@link PropertyEntity} and {@link RoomEntity}.
 */
@Entity
@Table(name = "room_rate")
@IdClass(RoomRateId.class)
class RoomRateEntity {

    @Id
    @Column(name = "property_id")
    private String propertyId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "segment")
    private RoomSegment segment;

    @Column(name = "nightly_rate", nullable = false)
    private BigDecimal nightlyRate;

    // char(3) in the schema (ISO 4217); Hibernate would otherwise validate against varchar.
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    protected RoomRateEntity() {
        // JPA
    }

    String propertyId() {
        return propertyId;
    }

    RoomSegment segment() {
        return segment;
    }

    BigDecimal nightlyRate() {
        return nightlyRate;
    }

    String currency() {
        return currency;
    }
}
