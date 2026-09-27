package com.marvel.hospitality.notification.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.notification.MockJwtDecoderConfiguration;
import com.marvel.hospitality.notification.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /notifications} (rest-api.md): rows are seeded straight into {@code notification} over JDBC (the write
 * path is the consumer integration test's job), so this class proves the read side, its property filtering and its
 * security. Same cached context as the other non-listening tests; every test uses its own reservation id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class NotificationControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void listsNotificationsOfReservationOldestFirst() throws Exception {
        String reservationId = newReservationId();
        seed(reservationId, "AMS01", "RESERVATION_CONFIRMED", "2026-10-01T09:15:04Z");
        seed(reservationId, "AMS01", "RESERVATION_CREATED_PENDING_PAYMENT", "2026-09-26T10:00:01Z");

        mvc.perform(get("/notifications").param("reservationId", reservationId).with(reader("AMS01")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].template").value("RESERVATION_CREATED_PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].reservationId").value(reservationId))
                .andExpect(jsonPath("$[0].propertyId").value("AMS01"))
                .andExpect(jsonPath("$[0].channel").value("LOG"))
                .andExpect(jsonPath("$[0].renderedText").value("text of RESERVATION_CREATED_PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-09-26T10:00:01Z"))
                .andExpect(jsonPath("$[1].template").value("RESERVATION_CONFIRMED"));
    }

    @Test
    void hidesNotificationsOfPropertiesNotInToken() throws Exception {
        String reservationId = newReservationId();
        seed(reservationId, "LIS01", "RESERVATION_CONFIRMED", "2026-10-01T09:15:04Z");

        mvc.perform(get("/notifications").param("reservationId", reservationId).with(reader("AMS01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/notifications").param("reservationId", reservationId).with(reader("*")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectsCallerWithoutReservationReadRoleWith403() throws Exception {
        mvc.perform(get("/notifications").param("reservationId", newReservationId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("bank:read"))
                                .jwt(j -> j.claim("properties", List.of("*")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingReservationIdIs400ValidationFailed() throws Exception {
        mvc.perform(get("/notifications").with(reader("AMS01")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tooLongReservationIdIs400ValidationFailed() throws Exception {
        mvc.perform(get("/notifications").param("reservationId", "P41454780").with(reader("AMS01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static JwtRequestPostProcessor reader(String property) {
        return jwt().authorities(new SimpleGrantedAuthority("reservation:read"))
                .jwt(j -> j.claim("properties", List.of(property)));
    }

    private static String newReservationId() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
    }

    private void seed(String reservationId, String propertyId, String template, String createdAt) {
        jdbc.sql("""
                        INSERT INTO notification
                            (id, event_id, reservation_id, property_id, channel, template, rendered_text, created_at)
                        VALUES (:id, :eventId, :reservationId, :propertyId, 'LOG', :template, :text, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("eventId", UUID.randomUUID().toString())
                .param("reservationId", reservationId)
                .param("propertyId", propertyId)
                .param("template", template)
                .param("text", "text of " + template)
                .param("createdAt", OffsetDateTime.parse(createdAt))
                .update();
    }
}
