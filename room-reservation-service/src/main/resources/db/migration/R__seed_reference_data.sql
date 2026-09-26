-- Repeatable seed migration (Flyway `R__` prefix reruns whenever its checksum changes).
-- Idempotent by design: ON CONFLICT DO UPDATE so re-running never duplicates rows or fails on existing data.
-- Source of truth: docs/contracts/database-schemas.md ("Seed" section under the reservation database).

INSERT INTO property (id, name, timezone, bank_account_number) VALUES
  ('AMS01', 'Marvel Amsterdam', 'Europe/Amsterdam', 'NL00MARV0000000001'),
  ('RTM01', 'Marvel Rotterdam', 'Europe/Amsterdam', 'NL00MARV0000000002')
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  timezone = EXCLUDED.timezone,
  bank_account_number = EXCLUDED.bank_account_number;

INSERT INTO room (property_id, room_number, segment)
SELECT p.id, r.room_number, r.segment
FROM (VALUES
  ('101', 'SMALL'),
  ('102', 'SMALL'),
  ('201', 'MEDIUM'),
  ('202', 'MEDIUM'),
  ('301', 'LARGE'),
  ('401', 'EXTRA_LARGE')
) AS r(room_number, segment)
CROSS JOIN (VALUES ('AMS01'), ('RTM01')) AS p(id)
ON CONFLICT (property_id, room_number) DO UPDATE SET
  segment = EXCLUDED.segment;

INSERT INTO room_rate (property_id, segment, nightly_rate, currency)
SELECT p.id, rt.segment, rt.nightly_rate, 'EUR'
FROM (VALUES
  ('SMALL', 80.00),
  ('MEDIUM', 120.00),
  ('LARGE', 180.00),
  ('EXTRA_LARGE', 260.00)
) AS rt(segment, nightly_rate)
CROSS JOIN (VALUES ('AMS01'), ('RTM01')) AS p(id)
ON CONFLICT (property_id, segment) DO UPDATE SET
  nightly_rate = EXCLUDED.nightly_rate,
  currency = EXCLUDED.currency;
