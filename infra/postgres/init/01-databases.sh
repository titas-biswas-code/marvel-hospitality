#!/usr/bin/env bash
# Runs once, on an empty data volume (postgres image entrypoint). Database-per-service (ADR-0003):
# each service gets its own role and database and owns its schema; no service can read another's data.
#
# REPLICATION is granted to the roles whose databases carry an outbox (reservation, payment) so the
# Debezium connectors (PR-04, ADR-0007) can open logical replication slots without a volume reset.
# Extensions (btree_gist) are created by each service's Flyway migrations, not here, so Testcontainers
# databases and compose databases are built the same way.
set -euo pipefail

create_db() {
  local name="$1" password="$2" replication="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
	CREATE ROLE ${name} LOGIN ${replication} PASSWORD '${password}';
	CREATE DATABASE ${name} OWNER ${name};
	REVOKE ALL ON DATABASE ${name} FROM PUBLIC;
SQL
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${name}" <<-SQL
	ALTER SCHEMA public OWNER TO ${name};
SQL
}

create_db reservation  "${RESERVATION_DB_PASSWORD}"  REPLICATION
create_db payment      "${PAYMENT_DB_PASSWORD}"      REPLICATION
create_db notification "${NOTIFICATION_DB_PASSWORD}" NOREPLICATION
