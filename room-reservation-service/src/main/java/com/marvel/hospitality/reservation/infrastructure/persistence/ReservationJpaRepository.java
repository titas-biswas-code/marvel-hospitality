package com.marvel.hospitality.reservation.infrastructure.persistence;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;

/** Spring Data repository behind {@link JpaReservationRepositoryAdapter}. Package-private: nothing else needs it. */
interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {

    /** Scoped by property first, so a reservation of another property is simply not found (ADR-0002). */
    Optional<ReservationEntity> findByPropertyIdAndReservationId(String propertyId, String reservationId);

    /** The exclusion constraint's predicate ({@code reservation_no_overlap}, V1__schema.sql) as a plain read. */
    @NativeQuery("""
            SELECT EXISTS (
              SELECT 1 FROM reservation
               WHERE property_id = :propertyId AND room_number = :roomNumber AND status <> 'CANCELLED'
                 AND stay && daterange(:startDate, :endDate, '[)'))
            """)
    boolean existsOverlapping(String propertyId, String roomNumber, LocalDate startDate, LocalDate endDate);

    /** Deliberately not scoped by property: a payment reference identifies one payment corporation-wide. */
    boolean existsByPaymentModeAndPaymentReference(PaymentMode paymentMode, String paymentReference);
}
