package com.marvel.hospitality.notification.application;

import com.marvel.hospitality.notification.domain.ReservationStatusNotice;
import java.util.UUID;

/** @param eventId the event's header {@code id}, the dedupe key (outbox-and-inbox.md) */
public record RecordNotificationCommand(UUID eventId, ReservationStatusNotice notice) {
}
