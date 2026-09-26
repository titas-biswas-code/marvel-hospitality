package com.marvel.hospitality.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * Proves platform/security-starter is wired into this service with this service's settings. The security behaviour
 * itself (401/403 codes, property checks, CORS) is tested once, in the starter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class SecurityWiringTest {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Test
    void rejectsMissingTokenWith401ProblemDetail() {
        RestClient client = RestClient.create("http://localhost:" + port);

        client.get().uri("/whoami").exchange((request, response) -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(new String(response.getBody().readAllBytes())).contains("\"code\":\"UNAUTHENTICATED\"");
            return null;
        });
    }

    @Test
    void whoamiReturnsRolesAndPropertiesFromToken() throws Exception {
        mvc.perform(get("/whoami").with(jwt()
                        .authorities(new SimpleGrantedAuthority("reservation:read"))
                        .jwt(j -> j.claim("properties", List.of("AMS01")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("reservation:read")))
                .andExpect(jsonPath("$.properties", contains("AMS01")));
    }

    @Test
    void referenceDataIsNotPublicInThisService() {
        // marvel.security.additional-public-paths is not set here; only reservation exposes /reference-data publicly.
        HttpStatusCode status = RestClient.create("http://localhost:" + port).get().uri("/reference-data")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
