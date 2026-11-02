package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.Plans;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.reportservice.support.ScenarioServiceStub;
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
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.reportservice.support.ScenarioServiceStub.ADDRESS;
import static org.reportservice.support.ScenarioServiceStub.PAYMENT;

/**
 * US-5 (T10, D12): runs are listed newest first with their counts; the run view carries the summary per feature;
 * results are listed by position, optionally by status, and shown with their steps. Read parameters are checked first.
 */
@ReportServiceIntegrationTest
class ReadRunsTest {

    private static final Map<String, Object> ZERO = Map.of("PASSED", 0, "FAILED", 0, "SKIPPED", 0, "PENDING", 0,
            "UNDEFINED", 0, "AMBIGUOUS", 0, "UNKNOWN", 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        AuthServiceStub.get().reset();
        ScenarioServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-5.2")
    @DisplayName("AC-5.2 The run view: durationMs is the sum of the results' durations; byFeature in first order")
    void theRunViewCarriesTheSummaryPerFeature() throws Exception {
        String runId = upload();

        MvcResult result = Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId);

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        Long sum = jdbc.queryForObject("SELECT sum(duration_ms) FROM test_case_results", Long.class);
        assertThat(((Number) JsonPath.read(body(result), "$.summary.durationMs")).longValue()).isEqualTo(sum);
        List<Map<String, Object>> byFeature = JsonPath.read(body(result), "$.summary.byFeature");
        assertThat(byFeature).hasSize(2);
        assertThat(byFeature.get(0)).containsEntry("featurePath", "features/checkout/payment.feature")
                .containsEntry("featureName", "Payment").containsEntry("total", 3);
        assertThat((Map<String, Object>) byFeature.get(0).get("counts"))
                .isEqualTo(with(ZERO, "PASSED", 2, "FAILED", 1));
        assertThat(byFeature.get(1)).containsEntry("featurePath", "features/checkout/address.feature")
                .containsEntry("total", 1);
        assertThat((Map<String, Object>) byFeature.get(1).get("counts")).isEqualTo(with(ZERO, "PENDING", 1));
        assertThat((Integer) JsonPath.read(body(result), "$.summary.total")).isEqualTo(4);
        assertThat(body(result)).contains("\"passRate\":50.0");
        assertThat((String) JsonPath.read(body(result), "$.environment.name")).isEqualTo("staging");
    }

    @Test
    @Tag("AC-5.1")
    @DisplayName("AC-5.1 Runs newest first, paged, with source, status, environment, build, total, counts, passRate")
    void runsAreListedNewestFirst() throws Exception {
        String uploaded = upload();
        String planned = JsonPath.read(body(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT,
                ADDRESS))), "$.id");
        String third = JsonPath.read(body(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("Third", "", ADDRESS))),
                "$.id");

        MvcResult first = Runs.read(mockMvc, DAN, CHECKOUT, "?page=0&size=2");
        MvcResult second = Runs.read(mockMvc, DAN, CHECKOUT, "?page=1&size=2");

        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(first), "$.page")).isZero();
        assertThat((Integer) JsonPath.read(body(first), "$.size")).isEqualTo(2);
        assertThat(((Number) JsonPath.read(body(first), "$.totalItems")).intValue()).isEqualTo(3);
        List<Map<String, Object>> items = JsonPath.read(body(first), "$.items");
        assertThat(items).extracting(item -> item.get("id")).containsExactly(third, planned);
        assertThat(items).allSatisfy(item -> assertThat(item).containsKeys("source", "status", "environment",
                "build", "total", "counts", "passRate").doesNotContainKey("summary"));
        assertThat(items.get(1)).containsEntry("total", 4).containsEntry("passRate", null);
        assertThat((Map<String, Object>) items.get(1).get("counts")).isEqualTo(with(ZERO, "UNKNOWN", 4));
        List<Map<String, Object>> rest = JsonPath.read(body(second), "$.items");
        assertThat(rest).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("id", uploaded).containsEntry("source", "AUTOMATED")
                    .containsEntry("total", 4);
            assertThat(((Number) item.get("passRate")).doubleValue()).isEqualTo(50.0);
        });
        assertThat(body(Runs.read(mockMvc, DAN, CHECKOUT, ""))).contains("\"size\":20");
    }

    @Test
    @Tag("AC-5.3")
    @DisplayName("AC-5.3 Results filtered by status FAILED: exactly OLD5, totalItems 1")
    void resultsCanBeFilteredByStatus() throws Exception {
        String runId = upload();

        MvcResult result = Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId + "/results?status=FAILED");

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat(((Number) JsonPath.read(body(result), "$.totalItems")).intValue()).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(body(result), "$.items[*].name"))
                .containsExactly("Pay with voucher OLD5");
        MvcResult all = Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId + "/results");
        assertThat((List<String>) JsonPath.read(body(all), "$.items[*].name")).containsExactly("Pay by card",
                "Pay with voucher SAVE10", "Pay with voucher OLD5", "Change address");
        assertThat((Integer) JsonPath.read(body(all), "$.size")).isEqualTo(100);
        assertThat(body(all)).doesNotContain("\"steps\"");
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("AC-2.2 The result detail of \"Pay by card\" lists its steps and hook; OLD5 shows its error")
    void theResultDetailListsItsRows() throws Exception {
        String runId = upload();
        List<String> ids = JsonPath.read(body(Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId + "/results")),
                "$.items[*].id");

        MvcResult card = Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId + "/results/" + ids.get(0));
        MvcResult old5 = Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId + "/results/" + ids.get(2));

        assertThat(card.getResponse().getStatus()).as(body(card)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(card), "$.durationMs")).isEqualTo(6);
        assertThat((Object) JsonPath.read(body(card), "$.featureId")).isNull();
        List<Map<String, Object>> steps = JsonPath.read(body(card), "$.steps");
        assertThat(steps).extracting(step -> step.get("kind"), step -> step.get("keyword"), step -> step.get("status"),
                step -> step.get("durationMs")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("STEP", "Given", "PASSED", 1),
                org.assertj.core.groups.Tuple.tuple("STEP", "When", "PASSED", 2),
                org.assertj.core.groups.Tuple.tuple("STEP", "Then", "PASSED", 3),
                org.assertj.core.groups.Tuple.tuple("AFTER_HOOK", null, "PASSED", 0));
        assertThat(steps.get(0)).containsEntry("text", "a basket").containsEntry("position", 1);
        assertThat((String) JsonPath.read(body(old5), "$.steps[1].errorMessage"))
                .isEqualTo("expected rejected but was accepted");
        assertThat((String) JsonPath.read(body(old5), "$.steps[2].status")).isEqualTo("SKIPPED");
    }

    @ParameterizedTest(name = "AC-5.4 GET {0}: 400 VALIDATION_FAILED for {1}")
    @CsvSource({"?size=0, size", "?size=101, size", "?page=-1, page", "/{id}/results?status=BROKEN, status",
            "/{id}/results?size=501, size", "/{id}/results?status=passed, status", "/{id}/results?page=-1, page"})
    @Tag("AC-5.4")
    void invalidReadParameters(String path, String field) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);

        Map<String, Object> problem = assertProblem(Runs.read(mockMvc, DAN, CHECKOUT, path.replace("{id}",
                run.id().toString())), 400, "VALIDATION_FAILED");

        assertThat((List<?>) problem.get("errors")).singleElement()
                .satisfies(error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo(field));
        assertThat(AuthServiceStub.get().received()).isEmpty();
    }

    @Test
    @Tag("AC-5.5")
    @DisplayName("AC-5.5 PASSED, PASSED and FAILED: passRate 66.7 with executed 3")
    void passRateRounding() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 3);
        Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(0), "{\"status\":\"PASSED\"}");
        Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(1), "{\"status\":\"PASSED\"}");
        Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(2), "{\"status\":\"FAILED\"}");

        MvcResult result = Runs.read(mockMvc, DAN, CHECKOUT, "/" + run.id());

        assertThat(body(result)).contains("\"passRate\":66.7");
        assertThat((Integer) JsonPath.read(body(result), "$.summary.executed")).isEqualTo(3);
    }

    @ParameterizedTest(name = "D13 GET {0}: 404 {1}")
    @CsvSource({"an unknown run, RUN_NOT_FOUND", "a Billing run, RUN_NOT_FOUND", "an unknown result, RESULT_NOT_FOUND",
            "the results of an unknown run, RUN_NOT_FOUND"})
    @Tag("AC-4.7")
    void unknownRunsAndResultsAreNotFound(String row, String code) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        RunData.Run billing = RunData.manual(jdbc, BILLING, "PLANNED", 1);
        String unknown = "00000000-0000-4000-8000-000000000000";
        String path = switch (row) {
            case "an unknown run" -> "/" + unknown;
            case "a Billing run" -> "/" + billing.id();
            case "an unknown result" -> "/" + run.id() + "/results/" + unknown;
            default -> "/" + unknown + "/results";
        };

        assertProblem(Runs.read(mockMvc, DAN, CHECKOUT, path), 404, code);
    }

    /** The AC-2.1 upload; returns the run id. */
    private String upload() throws Exception {
        String token = Uploads.activeToken(jdbc, CHECKOUT);
        MvcResult result = Uploads.upload(mockMvc, token,
                "name=Nightly&environment=staging&build=1.4.2%2B517&branch=main&commit=9FCEB02", CiSample.JSON);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(body(result), "$.id");
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... entries) {
        Map<String, Object> map = new java.util.HashMap<>(base);
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
