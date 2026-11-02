package org.reportservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AdjustableClock;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.Plans;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.reportservice.support.ScenarioServiceStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.reportservice.support.ScenarioServiceStub.ADDRESS;
import static org.reportservice.support.ScenarioServiceStub.PAYMENT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, manual runs (US-3, US-4, US-5; D6, D8, D10, D12): planning boundaries and scenario-service calls, lifecycle
 * transitions not in the Gherkin, comment rules, and summary arithmetic (half-up rounding, null durations, feature
 * order).
 */
@ReportServiceIntegrationTest
class QaManualRunTest {

    private final ScenarioServiceStub scenarios = ScenarioServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        scenarios.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @AfterEach
    void restore() {
        ((AdjustableClock) clock).reset();
        scenarios.reset();
    }

    // ---------- planning (US-3) ----------

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 qa: the tag is matched case-sensitively; @Smoke matches nothing -> 422 RUN_EMPTY")
    void tagsAreCaseSensitive() throws Exception {
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", ",\"tag\":\"@Smoke\"", PAYMENT, ADDRESS)),
                422, "RUN_EMPTY");
        assertThat(runs()).isZero();
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("AC-3.5 qa: exactly 2,000 test cases is 201 with 2,000 results in order")
    void exactlyTwoThousandIsAccepted() throws Exception {
        scenarios.extraPaymentTestCases(1_997);

        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("Big", "", PAYMENT));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat((Integer) JsonPath.read(body(result), "$.summary.total")).isEqualTo(2_000);
        assertThat(jdbc.queryForObject("SELECT max(position) FROM test_case_results", Integer.class)).isEqualTo(2_000);
        assertThat(jdbc.queryForObject("SELECT scenario_name FROM test_case_results WHERE position = 2000",
                String.class)).isEqualTo("Extra 1996");
    }

    @Test
    @Tag("AC-3.6")
    @DisplayName("AC-3.6 qa: a change to a feature that wasn't selected doesn't block the plan")
    void anUnselectedFeatureMayChange() throws Exception {
        scenarios.paymentVersionFromSecondList(3);

        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("Address only", "", ADDRESS));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT feature_version FROM test_case_results", Integer.class)).isEqualTo(1);
    }

    @Test
    @Tag("AC-3.8")
    @DisplayName("AC-3.8 qa: two features make 4 calls (list, test cases × 2 in request order, list)")
    void twoFeaturesMakeFourCalls() throws Exception {
        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", ADDRESS, PAYMENT));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(scenarios.received()).extracting(ScenarioServiceStub.Received::path).containsExactly(
                "/api/projects/" + CHECKOUT + "/features",
                "/api/projects/" + CHECKOUT + "/features/" + ADDRESS + "/test-cases",
                "/api/projects/" + CHECKOUT + "/features/" + PAYMENT + "/test-cases",
                "/api/projects/" + CHECKOUT + "/features");
        assertThat(jdbc.queryForList("SELECT scenario_name FROM test_case_results ORDER BY position", String.class))
                .containsExactly("Change address", "Pay by card", "Pay with voucher SAVE10", "Pay with voucher OLD5");
    }

    @ParameterizedTest(name = "AC-3.7 qa: scenario-service {0} -> 503 after one call per step, nothing stored")
    @CsvSource(delimiter = '|', value = {"LIST | 302 | ", "LIST | 200 | {\"items\":[]}", "TEST_CASES | 200 | [{}]",
            "TEST_CASES | 200 | [{\"id\":\"x\"}]", "LIST | 204 | ", "TEST_CASES | 404 | {}"})
    @Tag("AC-3.7")
    void oddScenarioAnswersFailClosed(ScenarioServiceStub.Kind kind, int status, String answer) throws Exception {
        scenarios.answer(kind, status, answer == null ? "" : answer);

        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT)), 503,
                "SCENARIO_SERVICE_UNAVAILABLE");

        assertThat(runs()).isZero();
        assertThat(scenarios.received()).hasSize(kind == ScenarioServiceStub.Kind.LIST ? 1 : 2);
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("AC-3.3 qa: validation precedes C5 and scenario-service (A3); upper-case feature ids are invalid")
    void validationFirst() throws Exception {
        AuthServiceStub.get().reset();
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, "{\"name\":\"R\",\"featureIds\":[\""
                + PAYMENT.toString().toUpperCase() + "\"]}"), 400, "VALIDATION_FAILED");
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", ",\"tag\":\"@a\\u00A0b\"", PAYMENT)), 400,
                "VALIDATION_FAILED");
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", ",\"tag\":\"@\"", PAYMENT)), 400,
                "VALIDATION_FAILED");
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", ",\"environment\":\"" + "e".repeat(101)
                + "\"", PAYMENT)), 400, "VALIDATION_FAILED");
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, "null"), 400, "MALFORMED_REQUEST");
        assertThat(AuthServiceStub.get().received()).isEmpty();
        assertThat(scenarios.received()).isEmpty();
        assertThat(runs()).isZero();
    }

    // ---------- lifecycle and recording (US-4, D8) ----------

    @Test
    @Tag("AC-4.2")
    @DisplayName("D8 deviation 2 qa: recording UNKNOWN resets status and comment but sets executedBy and finishedAt")
    void unknownKeepsWhoAndWhen() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        UUID result = run.results().get(0);
        Runs.record(mockMvc, HAL, CHECKOUT, run.id(), result, "{\"status\":\"FAILED\",\"comment\":\"x\"}");

        MvcResult reset = Runs.record(mockMvc, CAI, CHECKOUT, run.id(), result, "{\"status\":\"UNKNOWN\"}");

        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> json = JsonPath.read(body(reset), "$");
        assertThat(json).containsEntry("status", "UNKNOWN").containsEntry("comment", null)
                .containsEntry("executedBy", CAI.id().toString());
        assertThat(json.get("finishedAt")).isNotNull();
    }

    @Test
    @Tag("AC-4.1")
    @DisplayName("AC-4.1 qa: a later recording doesn't move startedAt")
    void startedAtIsSetOnce() throws Exception {
        AdjustableClock adjustable = (AdjustableClock) clock;
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 2);
        Instant first = Instant.parse("2031-02-03T04:05:06.000007Z");
        adjustable.set(first);
        Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(0), "{\"status\":\"PASSED\"}");
        adjustable.set(first.plus(1, ChronoUnit.HOURS));
        Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(1), "{\"status\":\"PASSED\"}");
        adjustable.reset();

        MvcResult view = Runs.read(mockMvc, DAN, CHECKOUT, "/" + run.id());
        assertThat((String) JsonPath.read(body(view), "$.startedAt")).isEqualTo(first.toString());
        assertThat((String) JsonPath.read(body(view), "$.status")).isEqualTo("IN_PROGRESS");
    }

    @ParameterizedTest(name = "D8 qa: {1} on a {0} run -> {2}")
    @CsvSource({"COMPLETED, cancel, 409", "CANCELLED, complete, 409", "CANCELLED, record, 409",
            "COMPLETED, record, 409", "IN_PROGRESS, complete, 200", "IN_PROGRESS, cancel, 200",
            "COMPLETED, complete, 200", "CANCELLED, cancel, 200"})
    @Tag("AC-4.4")
    void transitions(String from, String operation, int status) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, from, 1);
        String before = jdbc.queryForObject("SELECT finished_at::text FROM test_runs", String.class);

        MvcResult result = switch (operation) {
            case "cancel" -> Runs.cancel(mockMvc, GUS, CHECKOUT, run.id());
            case "complete" -> Runs.complete(mockMvc, HAL, CHECKOUT, run.id());
            default -> Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(0), "{\"status\":\"PASSED\"}");
        };

        if (status == 409) {
            assertProblem(result, 409, "RUN_CLOSED");
            assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo(from);
            assertThat(jdbc.queryForObject("SELECT finished_at::text FROM test_runs", String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT status FROM test_case_results", String.class)).isEqualTo("UNKNOWN");
        } else {
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
            String expected = operation.equals("cancel") ? "CANCELLED" : "COMPLETED";
            assertThat((String) JsonPath.read(body(result), "$.status")).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT finished_at FROM test_runs", Instant.class)).isNotNull();
            if (!from.equals("IN_PROGRESS")) {
                assertThat(jdbc.queryForObject("SELECT finished_at::text FROM test_runs", String.class))
                        .isEqualTo(before);
            }
        }
    }

    @Test
    @Tag("AC-4.7")
    @DisplayName("D13 qa: on an automated run, an unknown result is 404 before 409 RUN_NOT_MANUAL")
    void notFoundBeforeNotManual() throws Exception {
        RunData.Run run = RunData.automated(jdbc, CHECKOUT, 1);
        assertProblem(Runs.record(mockMvc, HAL, CHECKOUT, run.id(), UUID.randomUUID(), "{\"status\":\"PASSED\"}"),
                404, "RESULT_NOT_FOUND");
        assertProblem(Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(0), "{\"status\":\"PASSED\"}"),
                409, "RUN_NOT_MANUAL");
    }

    @ParameterizedTest(name = "AC-4.3 qa: comment {0} -> {1}")
    @CsvSource(delimiter = '|', value = {"\"<2000 grins>\" | 200", "\"<2001 grins>\" | 400", "\"a\\nb\\tc\" | 200",
            "\"a\\rb\" | 400", "\"a\\uD800\" | 400", "\"\\u0085\" | 400", "\"\" | 200", "null | 200"})
    @Tag("AC-4.3")
    void commentRules(String comment, int status) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        String json = "{\"status\":\"PASSED\",\"comment\":" + comment.replace("<2000 grins>",
                "\uD83D\uDE00".repeat(2000)).replace("<2001 grins>", "\uD83D\uDE00".repeat(2001)) + "}";

        MvcResult result = Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(0), json);

        if (status == 200) {
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        } else {
            Map<String, Object> problem = assertProblem(result, 400, "VALIDATION_FAILED");
            assertThat((List<Map<String, Object>>) problem.get("errors")).extracting(e -> e.get("field"))
                    .containsExactly("comment");
            assertThat(jdbc.queryForObject("SELECT status FROM test_case_results", String.class)).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo("PLANNED");
        }
    }

    // ---------- summary (US-5, D12) ----------

    @ParameterizedTest(name = "AC-5.5 qa: {0} passed of {1} executed -> passRate {2}")
    @CsvSource({"1, 8, 12.5", "1, 6, 16.7", "5, 6, 83.3", "2, 3, 66.7", "0, 4, 0.0", "1, 16, 6.3", "3, 3, 100.0"})
    @Tag("AC-5.5")
    void passRateIsHalfUp(int passed, int executed, String rate) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "IN_PROGRESS", executed + 1);
        for (int i = 0; i < executed; i++) {
            jdbc.update("UPDATE test_case_results SET status = ? WHERE id = ?", i < passed ? "PASSED" : "FAILED",
                    run.results().get(i));
        }

        String view = body(Runs.read(mockMvc, DAN, CHECKOUT, "/" + run.id()));
        String list = body(Runs.read(mockMvc, DAN, CHECKOUT, ""));

        assertThat(view).contains("\"passRate\":" + rate);
        assertThat((Integer) JsonPath.read(view, "$.summary.executed")).isEqualTo(executed);
        assertThat((Integer) JsonPath.read(view, "$.summary.total")).isEqualTo(executed + 1);
        assertThat(list).contains("\"passRate\":" + rate);
    }

    @Test
    @Tag("AC-5.2")
    @DisplayName("AC-5.2 qa: durationMs skips nulls, is null when all are null; byFeature keeps first-appearance order")
    void durationsAndFeatureOrder() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "IN_PROGRESS", 3);
        String none = body(Runs.read(mockMvc, DAN, CHECKOUT, "/" + run.id()));
        assertThat(none).contains("\"durationMs\":null").contains("\"passRate\":null");

        jdbc.update("UPDATE test_case_results SET feature_path = 'a.feature', feature_name = 'A', duration_ms = 5 "
                + "WHERE id = ?", run.results().get(1));
        jdbc.update("UPDATE test_case_results SET duration_ms = 7 WHERE id = ?", run.results().get(2));
        String view = body(Runs.read(mockMvc, DAN, CHECKOUT, "/" + run.id()));

        assertThat((Integer) JsonPath.read(view, "$.summary.durationMs")).isEqualTo(12);
        assertThat((List<String>) JsonPath.read(view, "$.summary.byFeature[*].featurePath"))
                .containsExactly("features/checkout/payment.feature", "a.feature");
        assertThat((List<Integer>) JsonPath.read(view, "$.summary.byFeature[*].total")).containsExactly(2, 1);
    }

    @Test
    @Tag("AC-5.4")
    @DisplayName("AC-5.4 qa: a far page within the int offset range is 200 and empty")
    void aFarPageIsEmpty() throws Exception {
        RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        MvcResult result = Runs.read(mockMvc, DAN, CHECKOUT, "?page=21474836&size=100");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(body(result), "$.items")).isEmpty();
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isEqualTo(1);
    }

    @ParameterizedTest(name = "RE13 qa: {0} {1} -> 200, no items, totalItems {2}")
    @CsvSource(delimiter = '|', value = {
            "CHECKOUT | ?page=2147483647&size=1                           | 3",
            "CHECKOUT | ?page=2147483647&size=100                         | 3",
            "CHECKOUT | ?page=21474836&size=100                           | 3",
            "BILLING  | ?page=2147483647&size=100                         | 0",
            "CHECKOUT | /{run}/results?page=4294967&size=500              | 4",
            "CHECKOUT | /{run}/results?page=4294968&size=500              | 4",
            "CHECKOUT | /{run}/results?page=2147483647&size=1             | 4",
            "CHECKOUT | /{run}/results?page=4294968&size=500&status=PASSED | 1",
            "CHECKOUT | /{run}/results?page=4294968&size=500&status=FAILED | 0"})
    @Tag("AC-5.1")
    void farPagesAreEmpty(String project, String relative, int total) throws Exception {
        RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "IN_PROGRESS", 4);
        jdbc.update("UPDATE test_case_results SET status = 'PASSED' WHERE id = ?", run.results().get(0));
        java.util.UUID projectId = project.equals("BILLING") ? org.reportservice.support.ReportsFixture.BILLING : CHECKOUT;

        MvcResult result = Runs.read(mockMvc, org.reportservice.support.ReportsFixture.ANA, projectId,
                relative.replace("{run}", run.id().toString()));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(body(result), "$.items")).isEmpty();
        assertThat(((Number) JsonPath.read(body(result), "$.totalItems")).longValue()).isEqualTo(total);
        String query = relative.substring(relative.indexOf('?') + 1);
        String page = query.replaceAll(".*page=(\\d+).*", "$1");
        String size = query.replaceAll(".*size=(\\d+).*", "$1");
        assertThat(((Number) JsonPath.read(body(result), "$.page")).longValue()).isEqualTo(Long.parseLong(page));
        assertThat(((Number) JsonPath.read(body(result), "$.size")).longValue()).isEqualTo(Long.parseLong(size));
    }

    @ParameterizedTest(name = "RE13 qa: far page {0} as {1} -> {2} {3}")
    @CsvSource(delimiter = '|', value = {
            "?page=2147483647&size=101         | DAN | 400 | VALIDATION_FAILED",
            "?page=2147483647&size=100         | IVY | 403 | FORBIDDEN",
            "/{unknown}/results?page=4294968&size=500 | DAN | 404 | RUN_NOT_FOUND",
            "/{run}/results?page=4294968&size=500&status=BROKEN | DAN | 400 | VALIDATION_FAILED"})
    @Tag("AC-5.4")
    void farPagesKeepTheOrderOfChecks(String relative, String user, int status, String code) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        org.reportservice.support.ReportsFixture.User caller = user.equals("IVY")
                ? org.reportservice.support.ReportsFixture.IVY : DAN;

        assertProblem(Runs.read(mockMvc, caller, CHECKOUT, relative.replace("{run}", run.id().toString())
                .replace("{unknown}", java.util.UUID.randomUUID().toString())), status, code);
    }

    @ParameterizedTest(name = "AC-5.4 qa: {0} -> 400 MALFORMED_REQUEST")
    @CsvSource({"?page=abc", "?size=1.5", "?page=99999999999"})
    @Tag("AC-5.4")
    void unreadableParameters(String query) throws Exception {
        assertProblem(Runs.read(mockMvc, DAN, CHECKOUT, query), 400, "MALFORMED_REQUEST");
    }

    @Test
    @Tag("AC-3.1")
    @DisplayName("AC-3.1 qa: the plan response's Location points at a run Dan can read with the same summary")
    void locationIsReadable() throws Exception {
        MvcResult plan = mockMvc.perform(post(Plans.runs(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, GUS.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(Plans.body("R", "", PAYMENT, ADDRESS))).andReturn();
        String location = plan.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).matches("/api/projects/" + CHECKOUT + "/runs/[0-9a-f-]{36}");

        String view = body(Runs.read(mockMvc, DAN, CHECKOUT, location.substring(Plans.runs(CHECKOUT).length())));
        assertThat((Object) JsonPath.read(view, "$.summary")).isEqualTo(JsonPath.read(body(plan), "$.summary"));
    }

    private int runs() {
        return jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class);
    }
}
