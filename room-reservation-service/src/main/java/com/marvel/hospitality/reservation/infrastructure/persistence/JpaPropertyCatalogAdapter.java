package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.application.PropertyCatalog;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link PropertyCatalog} over the Flyway-seeded {@code property}/{@code room}/{@code room_rate} tables. */
@Repository
class JpaPropertyCatalogAdapter implements PropertyCatalog {

    private final PropertyJpaRepository properties;
    private final RoomJpaRepository rooms;
    private final RoomRateJpaRepository rates;

    JpaPropertyCatalogAdapter(PropertyJpaRepository properties, RoomJpaRepository rooms, RoomRateJpaRepository rates) {
        this.properties = properties;
        this.rooms = rooms;
        this.rates = rates;
    }

    @Override
    public Optional<Property> findProperty(String propertyId) {
        return properties.findById(propertyId)
                .map(entity -> new Property(
                        entity.id(), entity.name(), ZoneId.of(entity.timezone()), entity.bankAccountNumber()));
    }

    @Override
    public Optional<Room> findRoom(String propertyId, String roomNumber) {
        return rooms.findByPropertyIdAndRoomNumber(propertyId, roomNumber)
                .map(entity -> new Room(entity.propertyId(), entity.roomNumber(), entity.segment()));
    }

    @Override
    public Optional<Money> findNightlyRate(String propertyId, RoomSegment segment) {
        return rates.findByPropertyIdAndSegment(propertyId, segment)
                .map(entity -> new Money(entity.nightlyRate(), entity.currency()));
    }
}
