package com.marvel.hospitality.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc // same context as SecurityWiringTest, so both share one Postgres container
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class ApplicationContextLoadsTest {

    @LocalServerPort
    int port;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesLivenessReadinessAndApiDocs() {
        RestClient client = RestClient.create("http://localhost:" + port);

        for (String path : new String[] {
                "/actuator/health/liveness", "/actuator/health/readiness", "/v3/api-docs", "/swagger-ui/index.html"}) {
            assertThat(client.get().uri(path).retrieve().toBodilessEntity().getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
