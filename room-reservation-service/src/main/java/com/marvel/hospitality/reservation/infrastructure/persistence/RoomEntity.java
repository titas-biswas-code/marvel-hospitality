package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.RoomSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA mapping of {@code room} (database-schemas.md): a physical room, seeded by Flyway with a fixed
 * {@link RoomSegment}. Read-only from this service's point of view, like {@link PropertyEntity}.
 */
@Entity
@Table(name = "room")
@IdClass(RoomId.class)
class RoomEntity {

    @Id
    @Column(name = "property_id")
    private String propertyId;

    @Id
    @Column(name = "room_number")
    private String roomNumber;

    // Never ORDINAL, never a Postgres ENUM type (ADR-0003, ADR-0004); persisted as varchar + CHECK.
    @Enumerated(EnumType.STRING)
    @Column(name = "segment", nullable = false)
    private RoomSegment segment;

    protected RoomEntity() {
        // JPA
    }

    String propertyId() {
        return propertyId;
    }

    String roomNumber() {
        return roomNumber;
    }

    RoomSegment segment() {
        return segment;
    }
}
