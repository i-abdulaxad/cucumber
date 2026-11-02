package org.reportservice.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.Plans;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.ScenarioServiceStub;
import org.reportservice.support.TestSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ScenarioServiceStub.PAYMENT;

/**
 * AC-3.7 (T8's test first, D6): scenario-service problems fail closed. Gus plans Payment while the scenario stub
 * misbehaves: 503 SCENARIO_SERVICE_UNAVAILABLE (401 for its 401), nothing stored, and a WARN line naming the failure
 * type without URL, port or token.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PlanningFailureTest {

    private final ScenarioServiceStub scenarios = ScenarioServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        AuthServiceStub.get().reset();
        scenarios.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @ParameterizedTest(name = "AC-3.7 the scenario stub {0}: {1} {2}, no run")
    @CsvSource(delimiter = '|', value = {
            "is not listening                    | 503 | SCENARIO_SERVICE_UNAVAILABLE",
            "answers 500 for test cases          | 503 | SCENARIO_SERVICE_UNAVAILABLE",
            "answers after 6 s                   | 503 | SCENARIO_SERVICE_UNAVAILABLE",
            "answers 200 with an unreadable body | 503 | SCENARIO_SERVICE_UNAVAILABLE",
            "answers 403                         | 503 | SCENARIO_SERVICE_UNAVAILABLE",
            "answers 404 for test cases          | 503 | SCENARIO_SERVICE_UNAVAILABLE",
            "answers 401                         | 401 | UNAUTHENTICATED"})
    @Tag("AC-3.7")
    void scenarioServiceProblemsFailClosed(String behaviour, int status, String code, CapturedOutput output)
            throws Exception {
        switch (behaviour.trim()) {
            case "is not listening" -> scenarios.stop();
            case "answers 500 for test cases" -> scenarios.answer(ScenarioServiceStub.Kind.TEST_CASES, 500, "{}");
            case "answers after 6 s" -> scenarios.delay(Duration.ofSeconds(6));
            case "answers 200 with an unreadable body" ->
                    scenarios.answer(ScenarioServiceStub.Kind.LIST, 200, "<html>ok</html>");
            case "answers 403" -> scenarios.answer(ScenarioServiceStub.Kind.LIST, 403, "{}");
            case "answers 404 for test cases" -> scenarios.answer(ScenarioServiceStub.Kind.TEST_CASES, 404, "{}");
            default -> scenarios.answer(ScenarioServiceStub.Kind.LIST, 401, "{}");
        }
        int logStart = output.getAll().length();
        long start = System.nanoTime();

        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
        assertProblem(result, status, code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isZero();
        RequestLog log = RequestLog.since(output, logStart);
        if (status == 503) {
            assertThat(log.entries()).filteredOn(line -> line.contains("Scenario service unavailable"))
                    .singleElement().satisfies(line -> assertThat(line).contains(" WARN "));
        }
        assertThat(log.hasStackTraceLine()).isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(GUS.token())
                || line.contains(String.valueOf(TestSettings.SCENARIO_SERVICE_PORT)) || line.contains("/features"));
    }
}
