package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.io.Serializable;
import java.util.Objects;

/**
 * {@link RoomRateEntity}'s composite primary key ({@code property_id}, {@code segment}), used as the
 * {@code @IdClass}. See {@link RoomId} for why this shape is needed.
 */
public class RoomRateId implements Serializable {

    private String propertyId;
    private RoomSegment segment;

    public RoomRateId() {
        // JPA
    }

    public RoomRateId(String propertyId, RoomSegment segment) {
        this.propertyId = propertyId;
        this.segment = segment;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoomRateId roomRateId)) {
            return false;
        }
        return Objects.equals(propertyId, roomRateId.propertyId) && segment == roomRateId.segment;
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyId, segment);
    }
}
