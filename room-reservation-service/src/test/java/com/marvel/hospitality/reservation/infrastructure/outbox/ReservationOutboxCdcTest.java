package com.marvel.hospitality.reservation.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole publish path of ADR-0006/0007 for this service, against real containers: a reservation's outbox row goes
 * through Debezium's Outbox Event Router, configured by the committed {@code infra/debezium/reservation-outbox.json}
 * (not a test copy), onto {@code reservation-status-changed}. Same Spring context and Postgres as the other
 * {@code @SpringBootTest}s; Kafka and Connect are started once per JVM by the shared fixture.
 *
 * <p>A bank-transfer reservation is used on purpose: its event has {@code null} fields ({@code previousStatus},
 * {@code reason}) and scale-2 amounts ({@code 360.00}, {@code 0.00}), the two things a JSON-expanding router would
 * silently change; the value must arrive exactly as the payload column holds it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
@Tag("cdc")
class ReservationOutboxCdcTest {

    private static final String TOPIC = "reservation-status-changed";
    private static final Path CONNECTOR = Path.of("../infra/debezium/reservation-outbox.json");

    @Autowired
    CreateReservationUseCase createReservation;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void outboxRowIsPublishedToConfiguredTopicWithKeyAndHeaders() {
        DebeziumCdc.createTopics(TOPIC);
        DebeziumCdc.registerConnector(CONNECTOR);

        // RTM01 room 301 in 2032: no other test books it.
        String reservationId = createReservation.create(new CreateReservationCommand("RTM01", "Ada Lovelace", "301",
                        java.time.LocalDate.parse("2032-06-01"), java.time.LocalDate.parse("2032-06-03"),
                        RoomSegment.LARGE, PaymentMode.BANK_TRANSFER, null))
                .reservation().reservationId().value();
        Map<String, Object> row = jdbc.sql("SELECT id::text AS id, payload::text AS payload, created_at FROM outbox_event WHERE aggregate_id = :id")
                .param("id", reservationId)
                .query().singleRow();

        ConsumerRecord<String, String> record = DebeziumCdc.awaitRecord(TOPIC, reservationId, Duration.ofSeconds(60));

        assertThat(record.key()).isEqualTo(reservationId);
        Headers headers = record.headers();
        assertThat(header(headers, "id")).isEqualTo(row.get("id"));
        assertThat(header(headers, "eventType")).isEqualTo("ReservationStatusChanged");
        assertThat(header(headers, "eventVersion")).isEqualTo("1");
        assertThat(header(headers, "producer")).isEqualTo("room-reservation-service");
        assertThat(header(headers, "propertyId")).isEqualTo("RTM01");
        assertThat(OffsetDateTime.parse(header(headers, "occurredAt")).toInstant())
                .isEqualTo(((java.sql.Timestamp) row.get("created_at")).toInstant());

        // Byte for byte what the payload column holds: nulls kept, amounts still at scale 2.
        assertThat(record.value()).isEqualTo(row.get("payload"));
        assertThat(jsonMapper.readTree(record.value())).isEqualTo(jsonMapper.readTree((String) row.get("payload")));
        assertThat(record.value())
                .contains("\"previousStatus\": null", "\"reason\": null",
                        "\"totalAmount\": 360.00", "\"amountReceived\": 0.00");
    }

    private static String header(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        assertThat(header.value()).as("header %s value", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
