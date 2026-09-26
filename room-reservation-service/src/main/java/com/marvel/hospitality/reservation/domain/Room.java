package com.marvel.hospitality.reservation.domain;

/** A physical room, seeded per property with a fixed {@link RoomSegment} (database-schemas.md). */
public record Room(String propertyId, String roomNumber, RoomSegment segment) {
}
