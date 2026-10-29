package org.reportservice.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D13 and standards §3 through the probe path: strict JSON (a number is no string), validation with {@code errors},
 * {@code requestId}, an archived project, Spring MVC's 404/405/415, database conflicts and unexpected failures; no
 * Problem contains internals or the submitted value.
 */
@ReportServiceIntegrationTest
class ProblemDetailsTest {

    private static final String PROBE = "/api/projects/" + CHECKOUT + "/probe";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetTheStub() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
    }

    @ParameterizedTest(name = "D13 body {0}: 400 {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"name\":42}           | MALFORMED_REQUEST",
            "{\"name\":true}         | MALFORMED_REQUEST",
            "{\"name\":             | MALFORMED_REQUEST",
            "{\"name\":\"   \"}      | VALIDATION_FAILED",
            "{}                     | VALIDATION_FAILED"})
    void strictJsonAndValidation(String json, String code) throws Exception {
        MvcResult result = mockMvc.perform(post(PROBE).header(HttpHeaders.AUTHORIZATION, GUS.bearer())
                .header("X-Request-Id", "r-13").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();

        Map<String, Object> problem = assertProblem(result, 400, code);
        assertThat(problem).containsEntry("requestId", "r-13");
        if (code.equals("VALIDATION_FAILED")) {
            assertThat((List<?>) problem.get("errors")).singleElement()
                    .satisfies(error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo("name"));
        }
        assertThat(AuthServiceStub.get().received()).isEmpty();
    }

    @Test
    @DisplayName("D7, D13: a write in an archived project is 409 PROJECT_ARCHIVED")
    void aWriteInAnArchivedProjectIsRefused() throws Exception {
        ReportsFixture.archive(CHECKOUT);
        try {
            MvcResult result = mockMvc.perform(post(PROBE).header(HttpHeaders.AUTHORIZATION, GUS.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"R\"}")).andReturn();

            assertProblem(result, 409, "PROJECT_ARCHIVED");
        } finally {
            ReportsFixture.reset();
        }
    }

    @ParameterizedTest(name = "D13 {0}: {1} {2}")
    @CsvSource({
            "unknown path, 404, NOT_FOUND",
            "unsupported method, 405, METHOD_NOT_ALLOWED",
            "text body, 415, UNSUPPORTED_MEDIA_TYPE",
            "unique violation, 409, CONFLICT",
            "lock timeout, 409, CONFLICT",
            "unexpected failure, 500, INTERNAL_ERROR"})
    void springAndDatabaseFailuresAreProblems(String row, int status, String code) throws Exception {
        MockHttpServletRequestBuilder request = switch (row) {
            case "unknown path" -> get(PROBE + "/x/y");
            case "unsupported method" -> delete(PROBE);
            case "text body" -> post(PROBE).contentType(MediaType.TEXT_PLAIN).content("name");
            case "unique violation" -> get(PROBE + "/failures/unique");
            case "lock timeout" -> get(PROBE + "/failures/lock");
            default -> get(PROBE + "/failures/unexpected");
        };

        MvcResult result = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, GUS.bearer())).andReturn();

        assertProblem(result, status, code);
        assertThat(body(result)).doesNotContain("secret-QA", "uk_builds", "lock timeout QA");
    }
}
