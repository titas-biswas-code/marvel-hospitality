-- One confirmed card payment backs at most one reservation (ADR-0011). Global, not per property: there is one
-- credit-card-payment-service for the whole corporation, so a payment used at AMS01 must not confirm a stay at RTM01.
-- Only CREDIT_CARD: cash and bank-transfer references are free text stored as-is and may repeat.
-- Mapped by this exact name to 409 PAYMENT_REFERENCE_ALREADY_USED (JpaReservationRepositoryAdapter).
CREATE UNIQUE INDEX reservation_credit_card_payment_reference_uq ON reservation (payment_reference)
  WHERE payment_mode = 'CREDIT_CARD';
