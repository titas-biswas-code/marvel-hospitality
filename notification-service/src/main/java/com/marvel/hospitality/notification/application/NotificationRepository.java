package com.marvel.hospitality.notification.application;

import com.marvel.hospitality.notification.domain.Notification;
import java.util.List;

public interface NotificationRepository {

    void add(Notification notification);

    /** @return the reservation's notifications, oldest first */
    List<Notification> findByReservationId(String reservationId);
}
