package com.marvel.hospitality.platform.outbox.cdc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the CDC path in a test the way {@code infra/connect-init} does in compose: registers a connector from its
 * real JSON file in {@code infra/debezium} (an idempotent {@code PUT /connectors/<name>/config}, the connector name
 * being the file name), waits until it and its task are {@code RUNNING}, and reads what it published.
 *
 * <p>Registering the committed file rather than a test copy is the point: the test fails when the deployed
 * connector configuration is wrong, not just when a hand-made test configuration is.
 */
public final class DebeziumCdc {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Duration CONNECTOR_START_TIMEOUT = Duration.ofSeconds(90);

    private DebeziumCdc() {
    }

    /** Registers (or re-registers, unchanged) the connector in {@code connectorJson} and waits until it runs. */
    public static void registerConnector(Path connectorJson) {
        String fileName = connectorJson.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".json".length());
        GenericContainer<?> connect = SharedContainers.connect();
        URI base = URI.create("http://" + connect.getHost() + ":" + connect.getMappedPort(8083) + "/connectors/" + name);
        try {
            HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/config"))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(Files.readString(connectorJson)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (put.statusCode() / 100 != 2) {
                throw new IllegalStateException("PUT " + base + "/config -> " + put.statusCode() + ": " + put.body());
            }
            awaitRunning(base);
        } catch (IOException e) {
            throw new IllegalStateException("Could not register connector " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted registering connector " + name, e);
        }
    }

    private static void awaitRunning(URI connector) {
        AtomicReference<String> last = new AtomicReference<>("");
        try {
            Awaitility.await().atMost(CONNECTOR_START_TIMEOUT).pollInterval(Duration.ofMillis(500))
                    .until(() -> isRunning(connector, last));
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException("Connector not RUNNING within " + CONNECTOR_START_TIMEOUT + ": " + last.get(), e);
        }
    }

    /** @throws IllegalStateException the connector's task failed; Awaitility rethrows it at once instead of waiting */
    private static boolean isRunning(URI connector, AtomicReference<String> last) throws IOException, InterruptedException {
        HttpResponse<String> status = HTTP.send(HttpRequest.newBuilder(URI.create(connector + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        last.set(status.body());
        if (status.statusCode() != 200) {
            return false;
        }
        JsonNode body = JSON.readTree(status.body());
        JsonNode tasks = body.path("tasks");
        if (tasks.size() > 0 && "FAILED".equals(tasks.get(0).path("state").asString())) {
            throw new IllegalStateException("Connector task failed: " + status.body());
        }
        return "RUNNING".equals(body.path("connector").path("state").asString())
                && tasks.size() > 0 && "RUNNING".equals(tasks.get(0).path("state").asString());
    }

    /**
     * Creates the topics if missing, as {@code infra/kafka/create-topics.sh} does in compose (3 partitions), so the
     * test sees the same topic layout instead of a broker auto-created one.
     */
    public static void createTopics(String... topics) {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers());
        try (Admin admin = Admin.create(config)) {
            for (String topic : topics) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException("Could not create topic " + topic, e.getCause());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted creating topics", e);
        }
    }

    /**
     * Reads {@code topic} from the beginning with a fresh consumer group until a record with {@code key} arrives.
     * Keys and values are read as raw strings, so a test sees the bytes exactly as Debezium wrote them.
     */
    public static ConsumerRecord<String, String> awaitRecord(String topic, String key, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "cdc-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic + " within " + timeout);
    }
}
