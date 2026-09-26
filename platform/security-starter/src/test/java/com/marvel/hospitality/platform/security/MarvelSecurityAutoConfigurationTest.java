package com.marvel.hospitality.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.platform.security.testapp.SecuredTestController;
import com.marvel.hospitality.platform.security.testapp.TestApplication;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The security behaviour every service gets from this starter, tested once here through a minimal Boot app that picks
 * the starter up via its AutoConfiguration.imports. Services only test that the starter is wired in.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "marvel.security.cors-allowed-origins=http://localhost:8088",
        "marvel.security.additional-public-paths=/reference-data"})
@AutoConfigureMockMvc
class MarvelSecurityAutoConfigurationTest {

    private static final String PROBLEM_TYPE_PREFIX = "https://marvel-hospitality/problems/";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void rejectsMissingTokenWith401ProblemDetail() throws Exception {
        expectProblem(mvc.perform(get("/whoami")), 401, "UNAUTHENTICATED")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void rejectsExpiredOrBadSignatureWith401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

        expectProblem(mvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer tampered.jwt.token")),
                        401, "UNAUTHENTICATED")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value(
                        "The bearer token is invalid, expired or not signed by the trusted issuer."));
    }

    @Test
    void whoamiReturnsRolesAndPropertiesFromToken() throws Exception {
        mvc.perform(get("/whoami").with(jwt()
                        .authorities(authorities("reservation:write", "reservation:read"))
                        .jwt(j -> j.subject("3f1c2a9e").claim("properties", List.of("AMS01")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("3f1c2a9e"))
                .andExpect(jsonPath("$.roles", contains("reservation:read", "reservation:write")))
                .andExpect(jsonPath("$.properties", contains("AMS01")));
    }

    @Test
    void roleMismatchIsForbidden() throws Exception {
        expectProblem(mvc.perform(get(SecuredTestController.PATH, "RTM01").with(jwt()
                        .authorities(authorities("reservation:read"))
                        .jwt(j -> j.claim("properties", List.of("RTM01"))))),
                403, "FORBIDDEN");
    }

    @Test
    void propertyNotInClaimIsForbiddenProperty() throws Exception {
        expectProblem(mvc.perform(get(SecuredTestController.PATH, "RTM01").with(jwt()
                        .authorities(authorities("reservation:write"))
                        .jwt(j -> j.claim("properties", List.of("AMS01"))))),
                403, "FORBIDDEN_PROPERTY");
    }

    @Test
    void propertyInClaimIsAllowed() throws Exception {
        mvc.perform(get(SecuredTestController.PATH, "AMS01").with(jwt()
                        .authorities(authorities("reservation:write"))
                        .jwt(j -> j.claim("properties", List.of("AMS01")))))
                .andExpect(status().isOk())
                .andExpect(content().string("AMS01"));
    }

    @Test
    void wildcardPropertyAllowsAnyProperty() throws Exception {
        mvc.perform(get(SecuredTestController.PATH, "RTM01").with(jwt()
                        .authorities(authorities("reservation:write"))
                        .jwt(j -> j.claim("properties", List.of("*")))))
                .andExpect(status().isOk())
                .andExpect(content().string("RTM01"));
    }

    @Test
    void publicPathsNeedNoToken() throws Exception {
        // The test app has no actuator and no /reference-data, so some answer 404 — the point is that security lets
        // them through instead of answering 401. Each service's SecurityWiringTest proves they return 200 there.
        for (String path : List.of(
                "/actuator/health", "/actuator/health/readiness", "/v3/api-docs", "/swagger-ui.html",
                "/swagger-ui/index.html")) {
            int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isNotEqualTo(401);
        }
    }

    @Test
    void additionalPublicPathsNeedNoToken() throws Exception {
        assertThat(mvc.perform(get("/reference-data")).andReturn().getResponse().getStatus()).isNotEqualTo(401);
        expectProblem(mvc.perform(get("/reference-data/but-not-below")), 401, "UNAUTHENTICATED");
    }

    @Test
    void openApiDocumentAdvertisesBearerAuth() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void allowsCorsPreflightFromTheUnifiedSwaggerUi() throws Exception {
        mvc.perform(options("/whoami")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8088")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8088"));
    }

    @Test
    void rejectsCorsPreflightFromOtherOrigins() throws Exception {
        mvc.perform(options("/whoami")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_PREFIX + code))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status));
    }

    private static GrantedAuthority[] authorities(String... roles) {
        return Arrays.stream(roles).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new);
    }
}
