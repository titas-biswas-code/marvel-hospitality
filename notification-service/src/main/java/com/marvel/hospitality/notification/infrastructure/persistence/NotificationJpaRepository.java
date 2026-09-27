package com.marvel.hospitality.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    /**
     * Inserts one {@code notification} row. Native because the entity is {@link
     * org.hibernate.annotations.Immutable} and never goes through JPA's persist/merge lifecycle; there is no
     * {@code ON CONFLICT} because the inbox already guarantees each event reaches this insert at most once (the
     * unique {@code event_id} would still refuse a second row).
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO notification
                (id, event_id, reservation_id, property_id, channel, template, rendered_text, created_at)
            VALUES
                (:id, :eventId, :reservationId, :propertyId, :channel, :template, :renderedText, :createdAt)
            """)
    void insert(
            @Param("id") UUID id,
            @Param("eventId") String eventId,
            @Param("reservationId") String reservationId,
            @Param("propertyId") String propertyId,
            @Param("channel") String channel,
            @Param("template") String template,
            @Param("renderedText") String renderedText,
            @Param("createdAt") Instant createdAt);

    List<NotificationEntity> findByReservationIdOrderByCreatedAtAscIdAsc(String reservationId);
}
