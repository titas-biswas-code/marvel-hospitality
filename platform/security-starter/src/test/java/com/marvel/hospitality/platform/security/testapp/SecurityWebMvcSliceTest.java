package com.marvel.hospitality.platform.security.testapp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 3 (docs/versions.md): {@code @WebMvcTest} only imports auto-configurations listed under the
 * {@code AutoConfigureMockMvc} imports key. This proves {@code MarvelSecurityAutoConfiguration} is registered
 * there too, so a service's controller slice tests get the same 401/403 problem-detail behaviour as a full
 * {@code @SpringBootTest} without needing to boot the whole application context.
 */
@WebMvcTest(SecuredTestController.class)
class SecurityWebMvcSliceTest {

    private static final String PROBLEM_TYPE_PREFIX = "https://marvel-hospitality/problems/";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void webMvcTestSliceAppliesMarvelSecurity() throws Exception {
        mvc.perform(get(SecuredTestController.PATH, "RTM01").with(jwt()
                        .authorities(new SimpleGrantedAuthority("reservation:read"))
                        .jwt(j -> j.claim("properties", List.of("RTM01")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_PREFIX + "FORBIDDEN"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get(SecuredTestController.PATH, "RTM01").with(jwt()
                        .authorities(new SimpleGrantedAuthority("reservation:write"))
                        .jwt(j -> j.claim("properties", List.of("AMS01")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_PREFIX + "FORBIDDEN_PROPERTY"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PROPERTY"));
    }
}
