package com.marvel.hospitality.reservation.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link RoomEntity}'s composite primary key ({@code property_id}, {@code room_number}), used as the
 * {@code @IdClass}. Needs value-based {@link #equals}/{@link #hashCode} and a no-arg constructor per the JPA spec.
 */
public class RoomId implements Serializable {

    private String propertyId;
    private String roomNumber;

    public RoomId() {
        // JPA
    }

    public RoomId(String propertyId, String roomNumber) {
        this.propertyId = propertyId;
        this.roomNumber = roomNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoomId roomId)) {
            return false;
        }
        return Objects.equals(propertyId, roomId.propertyId) && Objects.equals(roomNumber, roomId.roomNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyId, roomNumber);
    }
}
