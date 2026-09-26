package com.marvel.hospitality.platform.outbox.cdc;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres, one Kafka and one Kafka Connect per test JVM, shared by every Spring test context and every test
 * class of a service. Container start-up dominates test time, so nothing starts twice: Postgres starts on first use
 * (every integration test), Kafka and Connect only when a CDC test asks for them. Ryuk removes them when the JVM
 * exits; nothing here stops them earlier.
 *
 * <p>Because the database is shared, tests isolate themselves by unique ids (refs, reservation ids), never by
 * truncating tables or counting rows globally.
 *
 * <p>Postgres runs with {@code wal_level=logical} exactly like {@code infra/docker-compose.yml}, so every test runs
 * against the same database configuration as the compose stack and the CDC tests can open a replication slot on it.
 * All three containers share one Docker network under the host names the connector JSON in {@code infra/debezium}
 * uses ({@code postgres}), so the CDC tests register the real connector configuration unchanged.
 *
 * <p>Cross-run reuse ({@code withReuse(true)}) is deliberately not used: containers on a per-JVM network cannot be
 * reused (the network is gone on the next run), and a reused Connect/Kafka would carry replication slots and offsets
 * from earlier runs into the next one.
 */
public final class SharedContainers {

    /** Same images as {@code infra/docker-compose.yml} (docs/versions.md). */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17.11-alpine");
    public static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.3.1");
    public static final DockerImageName CONNECT_IMAGE = DockerImageName.parse("quay.io/debezium/connect:3.6.3.Final");

    /** Host names on the shared network; the Postgres one matches {@code database.hostname} in infra/debezium. */
    static final String POSTGRES_HOST = "postgres";
    static final String KAFKA_HOST = "kafka";
    static final String KAFKA_INTERNAL_LISTENER = KAFKA_HOST + ":19092";

    private static final Network NETWORK = Network.newNetwork();

    private static PostgreSQLContainer postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> connect;

    private SharedContainers() {
    }

    /**
     * The started Postgres. Database, user and password are all {@code database} (e.g. {@code reservation}), the
     * same role name the service uses in compose; the password is what {@link #connect()} hands the connector.
     *
     * @throws IllegalStateException if this JVM already started Postgres for a different database
     */
    public static synchronized PostgreSQLContainer postgres(String database) {
        if (postgres == null) {
            PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName(database)
                    .withUsername(database)
                    .withPassword(database)
                    .withNetwork(NETWORK)
                    .withNetworkAliases(POSTGRES_HOST)
                    // Same logical-decoding settings as infra/docker-compose.yml; fsync off as Testcontainers does.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "wal_level=logical",
                            "-c", "max_replication_slots=4", "-c", "max_wal_senders=4");
            container.start();
            postgres = container;
        } else if (!postgres.getDatabaseName().equals(database)) {
            throw new IllegalStateException("Shared Postgres already started for database '"
                    + postgres.getDatabaseName() + "', not '" + database + "'");
        }
        return postgres;
    }

    /** The started Kafka broker (KRaft), reachable from the host and, as {@code kafka:19092}, from Connect. */
    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            KafkaContainer container = new KafkaContainer(KAFKA_IMAGE)
                    .withNetwork(NETWORK)
                    .withNetworkAliases(KAFKA_HOST)
                    .withListener(KAFKA_INTERNAL_LISTENER);
            container.start();
            kafka = container;
        }
        return kafka;
    }

    /**
     * The started Debezium Kafka Connect worker (starts Kafka first). Configured like the {@code connect} service in
     * {@code infra/docker-compose.yml}, including the {@code env} config provider the connector JSON reads its
     * database password from ({@code ${env:<DB>_DB_PASSWORD}}).
     */
    public static synchronized GenericContainer<?> connect() {
        if (connect == null) {
            kafka();
            GenericContainer<?> container = new GenericContainer<>(CONNECT_IMAGE)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("connect")
                    .withExposedPorts(8083)
                    .withEnv("BOOTSTRAP_SERVERS", KAFKA_INTERNAL_LISTENER)
                    .withEnv("GROUP_ID", "marvel-connect-test")
                    .withEnv("CONFIG_STORAGE_TOPIC", "_connect-configs")
                    .withEnv("OFFSET_STORAGE_TOPIC", "_connect-offsets")
                    .withEnv("STATUS_STORAGE_TOPIC", "_connect-status")
                    .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("CONNECT_CONFIG_PROVIDERS", "env")
                    .withEnv("CONNECT_CONFIG_PROVIDERS_ENV_CLASS",
                            "org.apache.kafka.common.config.provider.EnvVarConfigProvider")
                    // Passwords of the shared Postgres (user = password = database name, see postgres()).
                    .withEnv("RESERVATION_DB_PASSWORD", "reservation")
                    .withEnv("PAYMENT_DB_PASSWORD", "payment")
                    .waitingFor(Wait.forHttp("/connectors").forPort(8083).forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(3)));
            container.start();
            connect = container;
        }
        return connect;
    }
}
