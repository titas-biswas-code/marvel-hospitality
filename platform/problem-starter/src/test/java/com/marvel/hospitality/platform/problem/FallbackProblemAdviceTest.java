package com.marvel.hospitality.platform.problem;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marvel.hospitality.platform.problem.testapp.ProblemTestController;
import com.marvel.hospitality.platform.problem.testapp.ServiceProblemAdvice;
import com.marvel.hospitality.platform.problem.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The problem-detail behaviour every service gets from this starter, tested once here through a minimal Boot app
 * that picks the starter up via its AutoConfiguration.imports. Services only need a wiring test.
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class FallbackProblemAdviceTest {

    private static final String PROBLEM_TYPE_PREFIX = "https://marvel-hospitality/problems/";

    @Autowired
    MockMvc mvc;

    @Test
    void serviceAdviceTakesPrecedenceOverFallback() throws Exception {
        expectProblem(mvc.perform(get(ProblemTestController.CUSTOM)), 409, ServiceProblemAdvice.CODE);
    }

    @Test
    void unexpectedExceptionBecomes500WithoutStackTrace() throws Exception {
        expectProblem(mvc.perform(get(ProblemTestController.RUNTIME)), 500, Problems.INTERNAL_ERROR)
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("sensitive internal detail"))));
    }

    @Test
    void unmappedConstraintViolationIsNever409() throws Exception {
        expectProblem(mvc.perform(get(ProblemTestController.CONSTRAINT)), 500, Problems.INTERNAL_ERROR);
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        expectProblem(mvc.perform(post(ProblemTestController.VALIDATED_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "type": "ALPHA"}
                                """)),
                400, Problems.VALIDATION_FAILED)
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void unknownEnumValueReturns400NamingTheField() throws Exception {
        expectProblem(mvc.perform(post(ProblemTestController.VALIDATED_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ada", "type": "GAMMA"}
                                """)),
                400, Problems.VALIDATION_FAILED)
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("ALPHA")));
    }

    @Test
    void invalidQueryParameterReturns400NamingTheParameter() throws Exception {
        expectProblem(mvc.perform(get(ProblemTestController.VALIDATED_PARAM).param("name", " ")),
                400, Problems.VALIDATION_FAILED)
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        expectProblem(mvc.perform(post(ProblemTestController.VALIDATED_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")),
                400, Problems.VALIDATION_FAILED)
                .andExpect(jsonPath("$.detail").value("Malformed JSON request body"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void unknownPathReturnsProblemWithCode() throws Exception {
        expectProblem(mvc.perform(get("/test/does-not-exist")), 404, "NOT_FOUND");
    }

    @Test
    void accessDeniedIsNotSwallowedByFallback() throws Exception {
        mvc.perform(get(ProblemTestController.ACCESS_DENIED)).andExpect(status().isForbidden());
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_PREFIX + code))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status));
    }
}
