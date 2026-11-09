package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.Plans;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.ScenarioServiceStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ScenarioServiceStub.ADDRESS;
import static org.reportservice.support.ScenarioServiceStub.INVOICE;
import static org.reportservice.support.ScenarioServiceStub.LEGACY;
import static org.reportservice.support.ScenarioServiceStub.PAYMENT;
import static org.reportservice.support.ScenarioServiceStub.R_OLD5;
import static org.reportservice.support.ScenarioServiceStub.R_SAVE10;
import static org.reportservice.support.ScenarioServiceStub.S_ADDRESS;
import static org.reportservice.support.ScenarioServiceStub.S_CARD;
import static org.reportservice.support.ScenarioServiceStub.S_VOUCHER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-3 (T8; D6, D10, D11, D13): Gus plans manual runs from scenario-service's features; the run stores snapshots of
 * the selected test cases, linked by feature, version, scenario and example row ids, all UNKNOWN; invalid or
 * unplannable plans store nothing.
 */
@ReportServiceIntegrationTest
class PlanRunTest {

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

    @Test
    @Tag("AC-3.1")
    @DisplayName("AC-3.1 A plan of Payment and Address: 201 PLANNED, 4 linked UNKNOWN results with step snapshots")
    void planAManualRunFromTwoFeatures() throws Exception {
        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("Release 1.4 regression",
                ",\"environment\":\"staging\"", PAYMENT, ADDRESS));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        Map<String, Object> run = JsonPath.read(body(result), "$");
        assertThat(run).containsEntry("source", "MANUAL").containsEntry("status", "PLANNED")
                .containsEntry("createdBy", GUS.id().toString()).containsEntry("name", "Release 1.4 regression")
                .containsEntry("startedAt", null).containsEntry("finishedAt", null);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(Plans.runs(CHECKOUT) + "/" + run.get("id"));
        assertThat((String) JsonPath.read(body(result), "$.environment.name")).isEqualTo("staging");
        assertThat((Integer) JsonPath.read(body(result), "$.summary.total")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body(result), "$.summary.counts.UNKNOWN")).isEqualTo(4);
        assertThat((Object) JsonPath.read(body(result), "$.summary.passRate")).isNull();

        List<Map<String, Object>> results = jdbc.queryForList("SELECT * FROM test_case_results ORDER BY position");
        assertThat(results).extracting(row -> row.get("scenario_name"), row -> row.get("scenario_id"),
                        row -> row.get("example_row_id"), row -> row.get("feature_id"), row -> row.get("feature_version"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Pay by card", S_CARD, null, PAYMENT, 2),
                        org.assertj.core.groups.Tuple.tuple("Pay with voucher SAVE10", S_VOUCHER, R_SAVE10, PAYMENT, 2),
                        org.assertj.core.groups.Tuple.tuple("Pay with voucher OLD5", S_VOUCHER, R_OLD5, PAYMENT, 2),
                        org.assertj.core.groups.Tuple.tuple("Change address", S_ADDRESS, null, ADDRESS, 1));
        assertThat(results).allSatisfy(row -> assertThat(row).containsEntry("status", "UNKNOWN")
                .containsEntry("attempt", 1));
        Map<String, Object> card = results.get(0);
        assertThat(card).containsEntry("feature_path", "features/checkout/payment.feature")
                .containsEntry("feature_name", "Payment").containsEntry("line", 12);
        assertThat(String.valueOf(card.get("tags"))).isEqualTo("[\"@checkout\", \"@smoke\"]");
        List<Map<String, Object>> steps = jdbc.queryForList("SELECT kind, keyword_text, step_text, "
                + "data_table::text AS data_table, doc_string, status FROM step_results WHERE test_case_result_id = ? "
                + "ORDER BY position", card.get("id"));
        assertThat(steps).extracting(row -> row.get("keyword_text"), row -> row.get("step_text")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Given", "a basket with these items:"),
                org.assertj.core.groups.Tuple.tuple("When", "the customer pays by card"),
                org.assertj.core.groups.Tuple.tuple("Then", "the receipt is:"));
        assertThat(steps).allSatisfy(row -> assertThat(row).containsEntry("kind", "STEP")
                .containsEntry("status", "UNKNOWN"));
        assertThat(steps.get(0)).containsEntry("data_table", "[[\"item\", \"price\"], [\"Mug\", \"8.00\"]]");
        assertThat(steps.get(2)).containsEntry("doc_string", "Total: 8.00");
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 A single tag narrows the run and is stored as tagExpression")
    void aSingleTagNarrowsTheRun() throws Exception {
        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("Smoke", ",\"tag\":\"@smoke\"", PAYMENT,
                ADDRESS));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(result), "$.tagExpression")).isEqualTo("@smoke");
        assertThat(jdbc.queryForList("SELECT scenario_name FROM test_case_results ORDER BY position", String.class))
                .containsExactly("Pay by card", "Change address");
    }

    @ParameterizedTest(name = "AC-3.3 body {0}: 400, no run, no scenario-service call")
    @ValueSource(strings = {"empty featureIds", "21 distinct ids", "a repeated id", "an id with a space",
            "an upper-case id", "a tag without @", "a tag with a space", "an empty name", "a name of 256 characters",
            "an environment of 101 characters", "name 7", "a featureIds entry 7", "no featureIds"})
    @Tag("AC-3.3")
    void invalidPlansStoreNothing(String row) throws Exception {
        String json = switch (row) {
            case "empty featureIds" -> "{\"name\":\"R\",\"featureIds\":[]}";
            case "21 distinct ids" -> {
                List<UUID> ids = new ArrayList<>();
                for (int i = 0; i < 21; i++) {
                    ids.add(UUID.randomUUID());
                }
                yield Plans.body("R", "", ids.toArray(UUID[]::new));
            }
            case "a repeated id" -> Plans.body("R", "", PAYMENT, PAYMENT);
            case "an id with a space" -> "{\"name\":\"R\",\"featureIds\":[\"" + PAYMENT + " \"]}";
            case "an upper-case id" -> "{\"name\":\"R\",\"featureIds\":[\"" + PAYMENT.toString().toUpperCase() + "\"]}";
            case "a tag without @" -> Plans.body("R", ",\"tag\":\"smoke\"", PAYMENT);
            case "a tag with a space" -> Plans.body("R", ",\"tag\":\"@smo ke\"", PAYMENT);
            case "an empty name" -> Plans.body("", "", PAYMENT);
            case "a name of 256 characters" -> Plans.body("n".repeat(256), "", PAYMENT);
            case "an environment of 101 characters" ->
                    Plans.body("R", ",\"environment\":\"" + "e".repeat(101) + "\"", PAYMENT);
            case "name 7" -> "{\"name\":7,\"featureIds\":[\"" + PAYMENT + "\"]}";
            case "a featureIds entry 7" -> "{\"name\":\"R\",\"featureIds\":[7]}";
            default -> "{\"name\":\"R\"}";
        };
        String code = row.equals("name 7") || row.equals("a featureIds entry 7") ? "MALFORMED_REQUEST"
                : "VALIDATION_FAILED";

        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, json), 400, code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isZero();
        assertThat(scenarios.received()).isEmpty();
        assertThat(AuthServiceStub.get().received()).isEmpty();
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("AC-3.4 Unplannable features: 422 FEATURE_NOT_PLANNABLE listing every such id in request order")
    void featuresThatCantBePlanned() throws Exception {
        UUID unknown = UUID.fromString("00000000-0000-4000-8000-000000000000");

        MvcResult result = Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT, LEGACY, INVOICE, unknown));

        Map<String, Object> problem = assertProblem(result, 422, "FEATURE_NOT_PLANNABLE");
        assertThat(((List<?>) problem.get("featureIds")).stream().map(String::valueOf).toList())
                .containsExactly(LEGACY.toString(), INVOICE.toString(), unknown.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isZero();
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("AC-3.5 No test case left is 422 RUN_EMPTY; more than 2,000 is 422 RUN_TOO_LARGE")
    void emptyAndOversizedRuns() throws Exception {
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", ",\"tag\":\"@nothing\"", PAYMENT)), 422,
                "RUN_EMPTY");
        scenarios.extraPaymentTestCases(1998);
        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT)), 422, "RUN_TOO_LARGE");
        scenarios.extraPaymentTestCases(1997);
        assertThat(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT)).getResponse().getStatus())
                .as("exactly 2,000").isEqualTo(201);
    }

    @Test
    @Tag("AC-3.6")
    @DisplayName("AC-3.6 A feature saved during planning: 409 FEATURE_CHANGED, nothing stored")
    void aFeatureSavedDuringPlanning() throws Exception {
        scenarios.paymentVersionFromSecondList(3);

        assertProblem(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("R", "", PAYMENT)), 409, "FEATURE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isZero();
    }

    @Test
    @Tag("AC-3.8")
    @DisplayName("AC-3.8 Exactly list, test cases, list; each with Gus's Authorization and X-Request-Id plan-1")
    void theCallersIdentityIsForwardedUnchanged() throws Exception {
        MvcResult result = mockMvc.perform(post(Plans.runs(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, GUS.bearer())
                .header("X-Request-Id", "plan-1").contentType(MediaType.APPLICATION_JSON)
                .content(Plans.body("R", "", PAYMENT))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(scenarios.received()).extracting(ScenarioServiceStub.Received::path).containsExactly(
                "/api/projects/" + CHECKOUT + "/features",
                "/api/projects/" + CHECKOUT + "/features/" + PAYMENT + "/test-cases",
                "/api/projects/" + CHECKOUT + "/features");
        assertThat(scenarios.received()).allSatisfy(call -> {
            assertThat(call.authorization()).isEqualTo(GUS.bearer());
            assertThat(call.requestId()).isEqualTo("plan-1");
            assertThat(call.method()).isEqualTo("GET");
        });
    }
}
