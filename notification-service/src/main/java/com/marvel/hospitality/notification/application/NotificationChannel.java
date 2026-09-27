package com.marvel.hospitality.notification.application;

import com.marvel.hospitality.notification.domain.Notification;

/**
 * Where a stored notification is sent. This service only logs ({@code LogNotificationChannel}); an e-mail channel
 * would be another implementation, looking the customer's address up by reservation (the event carries none).
 *
 * <p>{@link #deliver} is called after the notification's transaction has committed, never inside it: a real channel
 * is a remote call (ADR-0006, no transaction held across one), and a rolled-back notification must never have been
 * sent.
 */
public interface NotificationChannel {

    /** @return the name stored in {@code notification.channel}, at most 16 characters (e.g. {@code LOG}) */
    String name();

    void deliver(Notification notification);
}
