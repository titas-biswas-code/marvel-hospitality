package com.marvel.hospitality.reservation.application;

/** {@code 404 PROPERTY_NOT_FOUND}: the path names a property the entitlement allows but that is not seeded. */
public class PropertyNotFoundException extends RuntimeException {

    public PropertyNotFoundException(String propertyId) {
        super("Property " + propertyId + " does not exist.");
    }
}
