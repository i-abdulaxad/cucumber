package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;

/**
 * US-4 (T9; D8, D13, D14): testers record a status and comment per result; the first recording starts the run;
 * complete and cancel close it, idempotently; automated runs and closed runs refuse changes.
 */
@ReportServiceIntegrationTest
class RecordResultsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-4.4")
    @DisplayName("AC-4.4 Complete twice: same finishedAt, unrecorded stay UNKNOWN; record and cancel are then 409")
    void completeClosesTheRun() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 4);
        record(run, 0, "{\"status\":\"PASSED\"}");
        record(run, 1, "{\"status\":\"FAILED\"}");

        MvcResult first = Runs.complete(mockMvc, HAL, CHECKOUT, run.id());
        MvcResult second = Runs.complete(mockMvc, HAL, CHECKOUT, run.id());

        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(first), "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(body(second), "$.finishedAt"))
                .isEqualTo(JsonPath.read(body(first), "$.finishedAt")).isNotNull();
        assertThat((Integer) JsonPath.read(body(second), "$.summary.counts.UNKNOWN")).isEqualTo(2);
        assertProblem(record(run, 2, "{\"status\":\"PASSED\"}"), 409, "RUN_CLOSED");
        assertProblem(Runs.cancel(mockMvc, GUS, CHECKOUT, run.id()), 409, "RUN_CLOSED");
    }

    @Test
    @Tag("AC-4.1")
    @DisplayName("AC-4.1 The first recorded result starts the run")
    void theFirstRecordedResultStartsTheRun() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 2);

        MvcResult result = record(run, 0, "{\"status\":\"PASSED\"}");

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        Map<String, Object> recorded = JsonPath.read(body(result), "$");
        assertThat(recorded).containsEntry("status", "PASSED").containsEntry("executedBy", HAL.id().toString())
                .containsEntry("id", run.results().get(0).toString()).containsEntry("comment", null);
        assertThat(recorded.get("finishedAt")).isNotNull();
        assertThat(recorded).doesNotContainKey("steps");
        Map<String, Object> stored = jdbc.queryForMap("SELECT status, started_at FROM test_runs WHERE id = ?",
                run.id());
        assertThat(stored.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(stored.get("started_at")).isNotNull();
    }

    @Test
    @Tag("AC-4.2")
    @DisplayName("AC-4.2 Re-recording replaces the status; UNKNOWN with a null comment resets it")
    void reRecordingReplacesTheStatus() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 3);

        MvcResult failed = record(run, 2, "{\"status\":\"FAILED\",\"comment\":\"Voucher accepted\"}");
        MvcResult reset = record(run, 2, "{\"status\":\"UNKNOWN\",\"comment\":null}");

        assertThat((String) JsonPath.read(body(failed), "$.comment")).isEqualTo("Voucher accepted");
        assertThat((String) JsonPath.read(body(reset), "$.status")).isEqualTo("UNKNOWN");
        assertThat((Object) JsonPath.read(body(reset), "$.comment")).isNull();
        MvcResult complete = Runs.complete(mockMvc, HAL, CHECKOUT, run.id());
        assertThat((Integer) JsonPath.read(body(complete), "$.summary.counts.UNKNOWN")).isEqualTo(3);
    }

    @ParameterizedTest(name = "AC-4.3 body {0}: 400 {1}, nothing changed")
    @CsvSource(delimiter = '|', value = {
            "{\"status\":\"passed\"}                     | VALIDATION_FAILED",
            "{\"status\":\"PENDING\"}                    | VALIDATION_FAILED",
            "{\"status\":\"AMBIGUOUS\"}                  | VALIDATION_FAILED",
            "{}                                        | VALIDATION_FAILED",
            "{\"status\":\"PASSED\",\"comment\":\"<2,001 × c>\"} | VALIDATION_FAILED",
            "{\"status\":\"PASSED\",\"comment\":\"a\\u0007\"}   | VALIDATION_FAILED",
            "{\"status\":\"PASSED\",\"comment\":\"a\\ud800\"}   | VALIDATION_FAILED",
            "{\"status\":1}                              | MALFORMED_REQUEST"})
    @Tag("AC-4.3")
    void invalidRecordingsChangeNothing(String json, String code) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);

        assertProblem(record(run, 0, json.trim().replace("<2,001 × c>", "c".repeat(2001))), 400, code);
        assertThat(jdbc.queryForObject("SELECT status FROM test_case_results", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo("PLANNED");
        assertThat(AuthServiceStub.get().received()).isEmpty();
    }

    @Test
    @Tag("AC-4.3")
    @DisplayName("D8: a comment of exactly 2,000 code points with LF and TAB is accepted")
    void theCommentLimitsAreInclusive() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        String comment = "😀\n\t" + "c".repeat(1997);

        MvcResult result = record(run, 0, "{\"status\":\"PASSED\",\"comment\":\"😀\\n\\t" + "c".repeat(1997) + "\"}");

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(result), "$.comment")).isEqualTo(comment);
    }

    @Test
    @Tag("AC-4.5")
    @DisplayName("AC-4.5 Cancel a PLANNED run twice: both 200 CANCELLED; then complete is 409 RUN_CLOSED")
    void cancelClosesAPlannedRun() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);

        MvcResult first = Runs.cancel(mockMvc, GUS, CHECKOUT, run.id());
        MvcResult second = Runs.cancel(mockMvc, GUS, CHECKOUT, run.id());

        assertThat((String) JsonPath.read(body(first), "$.status")).isEqualTo("CANCELLED");
        assertThat((String) JsonPath.read(body(second), "$.status")).isEqualTo("CANCELLED");
        assertThat((String) JsonPath.read(body(second), "$.finishedAt"))
                .isEqualTo(JsonPath.read(body(first), "$.finishedAt"));
        assertProblem(Runs.complete(mockMvc, HAL, CHECKOUT, run.id()), 409, "RUN_CLOSED");
    }

    @ParameterizedTest(name = "AC-4.6 {0} on an automated run: 409 RUN_NOT_MANUAL")
    @ValueSource(strings = {"Hal records PASSED", "Hal completes", "Gus cancels"})
    @Tag("AC-4.6")
    void automatedRunsCantBeChangedByHand(String operation) throws Exception {
        RunData.Run run = RunData.automated(jdbc, CHECKOUT, 1);

        MvcResult result = switch (operation) {
            case "Hal records PASSED" -> record(run, 0, "{\"status\":\"PASSED\"}");
            case "Hal completes" -> Runs.complete(mockMvc, HAL, CHECKOUT, run.id());
            default -> Runs.cancel(mockMvc, GUS, CHECKOUT, run.id());
        };

        assertProblem(result, 409, "RUN_NOT_MANUAL");
    }

    @ParameterizedTest(name = "AC-4.7 recording at {0}: 404 {1}")
    @CsvSource({"an unknown run, RUN_NOT_FOUND", "a Billing run, RUN_NOT_FOUND",
            "run A with a result of run B, RESULT_NOT_FOUND"})
    @Tag("AC-4.7")
    void unknownRunsAndResults(String path, String code) throws Exception {
        RunData.Run runA = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        RunData.Run runB = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        RunData.Run billing = RunData.manual(jdbc, BILLING, "PLANNED", 1);

        MvcResult result = switch (path) {
            case "an unknown run" -> Runs.record(mockMvc, HAL, CHECKOUT,
                    UUID.fromString("00000000-0000-4000-8000-000000000000"), runA.results().get(0),
                    "{\"status\":\"PASSED\"}");
            case "a Billing run" -> Runs.record(mockMvc, HAL, CHECKOUT, billing.id(), billing.results().get(0),
                    "{\"status\":\"PASSED\"}");
            default -> Runs.record(mockMvc, HAL, CHECKOUT, runA.id(), runB.results().get(0),
                    "{\"status\":\"PASSED\"}");
        };

        assertProblem(result, 404, code);
    }

    private MvcResult record(RunData.Run run, int index, String json) throws Exception {
        return Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(index), json);
    }
}
