package com.marvel.hospitality.platform.problem.testapp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.platform.problem.Problems;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.test.autoconfigure.webmvc.SecurityMockMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@code MarvelProblemAutoConfiguration} is registered for the {@code @WebMvcTest} slice, not only for a
 * full {@code @SpringBootTest} (Task 3: the slice only imports auto-configurations listed under the
 * {@code AutoConfigureMockMvc} imports key). All of Spring Security's servlet auto-configurations are excluded here
 * on purpose — they are not what this test is about, and would otherwise secure every endpoint by default because
 * spring-security-test is also on this module's test classpath (see security-starter's own slice test for that
 * behaviour instead).
 */
@WebMvcTest(controllers = ProblemTestController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
        SecurityMockMvcAutoConfiguration.class})
class ProblemWebMvcSliceTest {

    @Autowired
    MockMvc mvc;

    @Test
    void fallbackAdviceIsAppliedInTheWebMvcSlice() throws Exception {
        mvc.perform(get(ProblemTestController.RUNTIME))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(Problems.INTERNAL_ERROR))
                .andExpect(jsonPath("$.type").value(Problems.TYPE_PREFIX + Problems.INTERNAL_ERROR));
    }
}
