package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.util.Optional;

/** Read-only port over the per-property reference data: properties, rooms and nightly rates (ADR-0002). */
public interface PropertyCatalog {

    Optional<Property> findProperty(String propertyId);

    Optional<Room> findRoom(String propertyId, String roomNumber);

    Optional<Money> findNightlyRate(String propertyId, RoomSegment segment);
}
