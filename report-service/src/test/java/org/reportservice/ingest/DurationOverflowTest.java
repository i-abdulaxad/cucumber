package org.reportservice.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CHECKOUT;

/**
 * T14 (RE11, Q-RC1; AC-2.8): durations whose sum overflows a test case's nanoseconds are invalid Cucumber JSON, 422
 * CUCUMBER_JSON_INVALID with the element's pointer, and nothing is stored; a sum of exactly {@code Long.MAX_VALUE}
 * is accepted.
 */
@ReportServiceIntegrationTest
class DurationOverflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @ParameterizedTest(name = "RE11 two step durations {0} and {1}: {2}")
    @CsvSource({
            "9223372036854775807, 9223372036854775807, 422",
            "4611686018427387904, 4611686018427387904, 422",
            "4611686018427387904, 4611686018427387903, 201"})
    @Tag("AC-2.8")
    void anOverflowingDurationSumIsInvalidCucumberJson(long first, long second, int status) throws Exception {
        String token = Uploads.activeToken(jdbc, CHECKOUT);
        String json = "[{\"uri\":\"a.feature\",\"elements\":[{\"type\":\"scenario\",\"name\":\"S\",\"steps\":["
                + "{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\",\"duration\":"
                + first + "}},"
                + "{\"keyword\":\"Given \",\"name\":\"b\",\"result\":{\"status\":\"passed\",\"duration\":" + second
                + "}}]}]}]";

        MvcResult result = Uploads.upload(mockMvc, token, "", json);

        if (status == 201) {
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT duration_ms FROM test_case_results", Long.class))
                    .isEqualTo(Long.MAX_VALUE / 1_000_000);
        } else {
            Map<String, Object> problem = assertProblem(result, 422, "CUCUMBER_JSON_INVALID");
            assertThat((List<?>) problem.get("errors")).singleElement()
                    .satisfies(error -> assertThat(((Map<?, ?>) error).get("pointer")).isEqualTo("$[0].elements[0]"));
            assertThat(Uploads.runs(jdbc)).isZero();
        }
    }
}
