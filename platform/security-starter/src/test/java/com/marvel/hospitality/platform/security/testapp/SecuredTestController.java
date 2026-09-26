package com.marvel.hospitality.platform.security.testapp;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Guarded exactly like the property-scoped business endpoints of the services. */
@RestController
public class SecuredTestController {

    public static final String PATH = "/test/properties/{propertyId}/write";

    @PreAuthorize("hasAuthority('reservation:write') and @propertyAccess.allowed(#propertyId)")
    @GetMapping(PATH)
    String write(@PathVariable String propertyId) {
        return propertyId;
    }
}
