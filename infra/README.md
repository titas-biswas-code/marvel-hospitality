# infra

Local infrastructure for marvel-hospitality: Postgres, Kafka (KRaft), Kafka Connect (Debezium), Keycloak,
Grafana `otel-lgtm`. Application services join the same compose file under the `apps` profile from PR-01
onward. Image and library versions: `docs/versions.md`.

## Quick start

```
make up                        # infra/.env created from .env.example if missing; brings up infra only
make up-apps                   # builds and brings up the application services (profile `apps`) too
make token USER=alice          # password-grant access token for a dev user (bob, carol also work)
make client-token CLIENT=bank-simulator   # client-credentials token for a service account
make down                      # stops everything (infra and `apps`), keeps volumes
make reset                     # wipes ALL volumes (postgres, kafka, keycloak) and brings infra back up
```

`infra/.env` is a local file, created once from `infra/.env.example` by the `up` target (or by hand:
`cp infra/.env.example infra/.env`). It is never committed; edit it locally to change any default.

## Ports

| Service | Host port | Notes |
|---|---|---|
| postgres | 5432 | one Postgres instance, three databases: `reservation`, `payment`, `notification` |
| kafka | 9094 | `EXTERNAL` listener, for host tools / IDE runs. Inside compose, other containers use `kafka:9092`. |
| kafka-ui | http://localhost:8090 | web UI, also shows Kafka Connect connectors |
| Kafka Connect REST | http://localhost:8083 | Debezium connectors `reservation-outbox`, `payment-outbox` (`/connectors?expand=status`) |
| Keycloak | http://localhost:8180 | admin console at `/admin`, login `admin`/`admin`. Management/health port 9000 is **not** published to the host. |
| Grafana (otel-lgtm) | http://localhost:3000 | OTLP ingest on 4317 (gRPC) / 4318 (HTTP). Not wired into any service until PR-10. |
| room-reservation-service | 8080 | app service, added under compose profile `apps` from PR-01 |
| bank-transfer-payment-service | 8081 | app service, added under compose profile `apps` from PR-01 |
| credit-card-payment-service | 9090 | app service, added under compose profile `apps` in PR-03 |
| notification-service | 8082 | app service, added under compose profile `apps` from PR-01 |
| swagger-ui | http://localhost:8088 | unified Swagger UI with a per-service dropdown, added under compose profile `apps` from PR-01 |

## What each container does

- **postgres** (`postgres:17.11-alpine`): one instance, db-per-service. `infra/postgres/init/01-databases.sh`
  runs once on first boot and creates the `reservation`, `payment`, `notification` databases plus one role
  per database (ADR-0003). The `reservation` and `payment` roles get `REPLICATION` so Debezium can create
  logical replication slots against them in PR-04. Server flags: `wal_level=logical`,
  `max_replication_slots=4`, `max_wal_senders=4`, `max_slot_wal_keep_size=1GB` (caps WAL retained if a
  replication slot stalls, so a broken connector cannot fill the disk). `btree_gist` is **not** created by
  the init script — it is created later by a Flyway migration (PR-02), per-database, as each service needs it.

- **kafka** (`apache/kafka:4.3.1`): single-node KRaft broker (no separate ZooKeeper). Automatic topic
  creation is disabled; topics are created explicitly by `kafka-init`.

- **kafka-init** (`apache/kafka:4.3.1`, one-shot): runs `infra/kafka/create-topics.sh`, which creates the
  4 domain topics plus their 4 `.DLT` topics (from `docs/contracts/events.md`), 3 partitions each, using
  `--if-not-exists` so re-runs are safe. Expected end state in `docker compose ps` is `Exited (0)` — this
  is not a failure.

- **kafka-ui** (`kafbat/kafka-ui:v1.5.0`): browses topics/messages and shows registered Kafka Connect
  connectors.

- **connect** (`quay.io/debezium/connect:3.6.3.Final`): Kafka Connect with Debezium bundled (Postgres
  connector + Outbox Event Router). Publishes each service's `outbox_event` rows to the topic named in the
  row (ADR-0007). Connector configs: `infra/debezium/<connector-name>.json`; they read the database password
  from the worker's environment (`${env:PAYMENT_DB_PASSWORD}`, Kafka's `EnvVarConfigProvider`), so no secret is
  in the JSON.

- **connect-init** (same image, profile `apps`, one-shot): `infra/connect-init/register-connectors.sh` does an
  idempotent `PUT /connectors/<name>/config` for every file in `infra/debezium/` and waits until each connector
  and its task are `RUNNING`. It runs only after both outbox services are healthy, because a connector's
  filtered publication needs the `outbox_event` table their Flyway migrations create. Rows written before it
  runs are not lost: the connector's initial snapshot publishes them. `make up-apps` runs it in the foreground
  after everything else is healthy (`up --wait` would count its normal exit as a failure), so a connector that
  does not reach `RUNNING` fails the make target. Re-run by hand (e.g. after editing a connector JSON, or when
  the services run from the IDE instead of compose):
  `docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm --no-deps connect-init`.

- **keycloak** (`quay.io/keycloak/keycloak:26.7.4`): IdP for the `marvel` realm. Started with
  `--import-realm`, which imports `infra/keycloak/realm/marvel-realm.json` **only if the realm does not
  already exist** (see "Reset" below). See `infra/keycloak/README.md` for how the realm file is produced
  and how to get tokens.

- **otel-lgtm** (`grafana/otel-lgtm:0.34.0`): Grafana + Loki + Tempo + Prometheus bundle, OTLP receiver.
  Not consumed by any service until observability is wired up in PR-10.

Every long-running container above has a Docker healthcheck; `kafka-init` and `connect-init` are the
exceptions and are expected to exit successfully rather than stay healthy.

## Application images

`room-reservation-service`, `bank-transfer-payment-service` and `notification-service` are built by compose
(`make up-apps` passes `--build`) with the **repo root** as build context, because they compile the shared
`platform/` starters from source (ADR-0001); the root `.dockerignore` keeps build output and `infra/.env` out of
the context. Each runs its `local` Spring profile, which points at the compose hostnames (`postgres`, `kafka`,
`keycloak`). After changing code in a service or in `platform/`, re-run `make up-apps` to rebuild and restart.

## Keycloak hostname / issuer

This is the part most likely to break silently, so it is spelled out here.

Keycloak is configured with `KC_HOSTNAME=http://localhost:8180` (Keycloak 26 "hostname v2" syntax — a full
URL, not just a hostname: https://www.keycloak.org/server/hostname), `KC_HOSTNAME_STRICT=false`, and
`KC_HTTP_ENABLED=true`. This means **every token Keycloak issues has `iss = http://localhost:8180/realms/marvel`**,
regardless of which network path was used to reach it.

- A token requested from the host (`curl http://localhost:8180/...`) gets `iss = http://localhost:8180/realms/marvel`.
- A token requested from inside the compose network (`http://keycloak:8080/...`) gets the **same** `iss`, because
  `KC_HOSTNAME` fixes it — verified: `http://keycloak:8080/realms/marvel/.well-known/openid-configuration` reports
  `issuer: http://localhost:8180/realms/marvel`, and `http://keycloak:8080/realms/marvel/protocol/openid-connect/certs`
  returns 200 from inside the network. (The discovery document itself advertises `localhost:8180` URLs, which is
  why services must not rely on discovery for the key set.)

Application services (added from PR-01) therefore set **both**:

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: http://localhost:8180/realms/marvel
spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://keycloak:8080/realms/marvel/protocol/openid-connect/certs
```

`issuer-uri` alone would make Spring Security try to fetch the OIDC discovery document from
`http://localhost:8180` **from inside the container**, where `localhost` is the container itself, not the
host — that fetch fails. Setting `jwk-set-uri` explicitly skips discovery and fetches keys over the compose
network instead, while the `iss` claim in the token still matches `issuer-uri` because both were minted
with the same `KC_HOSTNAME`.

## Unified Swagger UI

Open http://localhost:8088, pick the service in the "Select a definition" dropdown (top left), then
"Authorize" with a token from `make token USER=alice` (or `make token` for the default user). This works
because each service's `local` profile allows CORS from `http://localhost:8088` only
(`marvel.security.cors-allowed-origins`), so the browser can fetch `/v3/api-docs` and call the API
cross-origin from the shared UI. Each service still serves its own Swagger UI too, at its own
`/swagger-ui.html` — the shared one at :8088 is a dev convenience, not a replacement.

## Reset

Keycloak's `--import-realm` only imports `marvel-realm.json` when the realm does not already exist in its
database (import strategy `IGNORE_EXISTING`) — i.e. only on a fresh `keycloak-data` volume. Once the realm
exists, edits made through the admin console or a stale `bootstrap.sh` run are **not** overwritten by a
restart.

`make reset` runs:

```
docker compose --profile apps down -v --remove-orphans   # wipes postgres-data, kafka-data, keycloak-data
docker compose up -d --wait                                # fresh containers, realm re-imported
```

Use it whenever you want a clean slate, or to verify that a realm change committed via
`infra/keycloak/export.sh` actually imports correctly (see `infra/keycloak/README.md`).

## Runbook: replication slots (Debezium)

Each connector owns a logical replication slot in Postgres (`reservation_outbox`, `payment_outbox`). While a
connector is stopped, Postgres keeps the WAL its slot still needs, so events are delayed, not lost. The retained
WAL is capped by `max_slot_wal_keep_size=1GB`: past that, Postgres **invalidates** the slot (to protect its disk)
and the connector can no longer resume from it.

```
# lag per slot (bytes of WAL not yet confirmed by the connector)
docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres psql -U postgres -c \
  "select slot_name, database, active, wal_status, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) as lag from pg_replication_slots"
# connector and task state
curl -s localhost:8083/connectors?expand=status | jq
```

Recreating a connector whose slot is invalidated (`wal_status = lost`) or broken:

```
curl -X DELETE localhost:8083/connectors/payment-outbox
docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres psql -U postgres -d payment -c \
  "select pg_drop_replication_slot('payment_outbox')"
docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm --no-deps connect-init
```

The new connector snapshots the outbox table (rows from the last 7 days, see the purge job) and then streams again.
Events can therefore be published twice; consumers deduplicate (inbox, ADR-0006). Events whose rows were already
purged and whose WAL was lost are gone from Kafka; the ledger/reservation tables still hold the state.

## Secrets

Everything in `infra/.env.example` is a local-development default, not a secret — passwords, client
secrets and the Keycloak admin credentials are all fixed, publicly-visible values meant only for a laptop.
Real deployments inject equivalent values from a secret manager (Vault, AWS Secrets Manager, Kubernetes
Secrets) and never reuse anything from this file.
