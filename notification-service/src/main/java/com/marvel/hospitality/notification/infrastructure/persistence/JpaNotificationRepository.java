package com.marvel.hospitality.notification.infrastructure.persistence;

import com.marvel.hospitality.notification.application.NotificationRepository;
import com.marvel.hospitality.notification.domain.Notification;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class JpaNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository repository;

    JpaNotificationRepository(NotificationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void add(Notification notification) {
        repository.insert(notification.id(), notification.eventId().toString(), notification.reservationId(),
                notification.propertyId(), notification.channel(), notification.template().name(),
                notification.renderedText(), notification.createdAt());
    }

    @Override
    public List<Notification> findByReservationId(String reservationId) {
        return repository.findByReservationIdOrderByCreatedAtAscIdAsc(reservationId).stream()
                .map(NotificationEntity::toDomain)
                .toList();
    }
}
