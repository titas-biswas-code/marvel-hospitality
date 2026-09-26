package com.marvel.hospitality.reservation.application;

/** {@code 404 ROOM_NOT_FOUND}: room numbers are only unique within a property. */
public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException(String propertyId, String roomNumber) {
        super("Room " + roomNumber + " does not exist in property " + propertyId + ".");
    }
}
