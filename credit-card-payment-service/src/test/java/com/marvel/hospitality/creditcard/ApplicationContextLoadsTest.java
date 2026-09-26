package com.marvel.hospitality.creditcard;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness", "/v3/api-docs"}) {
            assertThat(client.get().uri(path).retrieve().toBodilessEntity().getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void servesCorrectedSpecAtApiDocs() {
        RestClient client = RestClient.create("http://localhost:" + port);

        String body = client.get().uri("/v3/api-docs").retrieve().body(String.class);

        assertThat(body).contains("retrievePaymentStatus", "http://localhost:9090/credit-card-payment-api");
    }

    @Test
    void servesSwaggerUi() {
        // The default RestClient request factory does not follow redirects; /swagger-ui.html 302s to the
        // actual index page, so this client is built on a JDK HttpClient configured to follow it
        // (springdoc.enable-default-api-docs=false only disables the generated OpenAPI resource -
        // OpenApiSpecController serves the corrected spec instead - Swagger UI itself must still work).
        HttpClient redirectFollowingHttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        RestClient client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(redirectFollowingHttpClient))
                .baseUrl("http://localhost:" + port)
                .build();

        assertThat(client.get().uri("/swagger-ui.html").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
