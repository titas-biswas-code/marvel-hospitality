-- GET /notifications?reservationId= reads one reservation's notifications, oldest first
-- (docs/contracts/database-schemas.md).
CREATE INDEX notification_reservation_idx ON notification (reservation_id, created_at);
