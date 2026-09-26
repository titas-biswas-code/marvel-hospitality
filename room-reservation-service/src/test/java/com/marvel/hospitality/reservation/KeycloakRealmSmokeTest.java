package com.marvel.hospitality.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.platform.security.WhoamiController.WhoamiResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the committed realm export is valid end to end: Keycloak imports it, issues a real token for a dev
 * user, and this service validates it (signature via JWKS, issuer) and derives roles and properties from it.
 * The only test that talks to Keycloak (contracts/security.md).
 */
@Tag("keycloak")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class KeycloakRealmSmokeTest {

    // Same image as infra/docker-compose.yml (docs/versions.md).
    static final DockerImageName KEYCLOAK_IMAGE = DockerImageName.parse("quay.io/keycloak/keycloak:26.7.4");
    // Keycloak requires the import file to be named <realm>-realm.json.
    static final Path REALM_EXPORT = Path.of("../infra/keycloak/realm/marvel-realm.json");
    // Local dev default from infra/.env.example, baked into the realm export.
    static final String BANK_SIMULATOR_SECRET = "bank-simulator-dev-secret";

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(requireRealmExport(), "/opt/keycloak/data/import/marvel-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void keycloakIssuer(DynamicPropertyRegistry registry) {
        // Without KC_HOSTNAME the issuer follows the URL the token is requested from, i.e. the mapped port.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakRealmSmokeTest::realmUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realmUrl() + "/protocol/openid-connect/certs");
    }

    @LocalServerPort
    int port;

    @Test
    void aliceTokenFromCommittedRealmIsAcceptedWithHerRolesAndProperties() {
        WhoamiResponse whoami = whoami(token(Map.of(
                "grant_type", "password", "client_id", "marvel-postman", "username", "alice", "password", "password")));

        assertThat(whoami.username()).isEqualTo("alice");
        assertThat(whoami.roles()).contains("reservation:read", "reservation:write");
        assertThat(whoami.properties()).containsExactly("AMS01", "RTM01");
    }

    @Test
    void bankSimulatorServiceAccountCarriesIngestRoleAndAllProperties() {
        WhoamiResponse whoami = whoami(token(Map.of(
                "grant_type", "client_credentials", "client_id", "bank-simulator", "client_secret", BANK_SIMULATOR_SECRET)));

        assertThat(whoami.roles()).contains("bank:ingest");
        assertThat(whoami.properties()).containsExactly("*");
    }

    private WhoamiResponse whoami(String accessToken) {
        return RestClient.create("http://localhost:" + port).get().uri("/whoami")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(WhoamiResponse.class);
    }

    private static String token(Map<String, String> grant) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        grant.forEach(form::add);
        Map<String, Object> response = RestClient.create().post().uri(realmUrl() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        assertThat(response).containsKey("access_token");
        return (String) response.get("access_token");
    }

    private static String realmUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/marvel";
    }

    private static MountableFile requireRealmExport() {
        if (!Files.isRegularFile(REALM_EXPORT)) {
            throw new IllegalStateException("Realm export not found at " + REALM_EXPORT.toAbsolutePath()
                    + "; run the tests from the service directory of a full repo checkout");
        }
        return MountableFile.forHostPath(REALM_EXPORT);
    }
}
