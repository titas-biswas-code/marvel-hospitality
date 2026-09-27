package com.marvel.hospitality.notification.infrastructure.persistence;

import com.marvel.hospitality.notification.domain.Notification;
import com.marvel.hospitality.notification.domain.NotificationTemplate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Read model of a {@code notification} row. A notification is written once, through {@link
 * NotificationJpaRepository#insert} (the inbox already guarantees one row per event), so the entity is
 * {@link Immutable}: Hibernate never writes it back.
 */
@Entity
@Immutable
@Table(name = "notification")
class NotificationEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "reservation_id", nullable = false)
    private String reservationId;

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "template", nullable = false)
    private String template;

    @Column(name = "rendered_text", nullable = false, columnDefinition = "text")
    private String renderedText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationEntity() {
    }

    Notification toDomain() {
        return new Notification(id, UUID.fromString(eventId), reservationId, propertyId, channel,
                NotificationTemplate.valueOf(template), renderedText, createdAt);
    }
}
