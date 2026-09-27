package com.marvel.hospitality.notification.application;

import com.marvel.hospitality.notification.domain.Notification;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListNotificationsUseCase {

    private final NotificationRepository notifications;

    public ListNotificationsUseCase(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * @param entitledToProperty whether the caller may see a property's data (ADR-0002); notifications of other
     *        properties are left out, so a caller cannot even learn that the reservation exists elsewhere
     * @return the reservation's notifications the caller may see, oldest first
     */
    @Transactional(readOnly = true)
    public List<Notification> list(String reservationId, Predicate<String> entitledToProperty) {
        return notifications.findByReservationId(reservationId).stream()
                .filter(notification -> entitledToProperty.test(notification.propertyId()))
                .toList();
    }
}
