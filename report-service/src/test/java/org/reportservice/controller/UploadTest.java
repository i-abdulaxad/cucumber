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
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.TestSettings;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-2 (T7; D5, D8, D9, D11, D13): the CI upload stores one completed AUTOMATED run with unlinked snapshot results, in
 * D13's order of checks, and nothing at all when a check fails. The read endpoints come in T10, so results are checked
 * in the database.
 */
@ReportServiceIntegrationTest
class UploadTest {

    private static final String AC_2_1_QUERY =
            "name=Nightly&environment=staging&build=1.4.2%2B517&branch=main&commit=9FCEB02";

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String t1;

    @BeforeEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        t1 = Uploads.activeToken(jdbc, CHECKOUT);
    }

    @AfterEach
    void unarchive() {
        ReportsFixture.reset();
    }

    @Test
    @Tag("AC-2.1")
    @DisplayName("AC-2.1 The upload creates a completed automated run with environment, build and summary")
    void uploadCreatesACompletedAutomatedRun() throws Exception {
        MvcResult result = mockMvc.perform(post(URI.create(Uploads.PATH + "?" + AC_2_1_QUERY))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + t1).header("X-Request-Id", "up-1")
                .contentType(MediaType.APPLICATION_JSON).content(CiSample.JSON)).andReturn();

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        Map<String, Object> run = JsonPath.read(body(result), "$");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/projects/" + CHECKOUT + "/runs/" + run.get("id"));
        assertThat(run).containsEntry("source", "AUTOMATED").containsEntry("status", "COMPLETED")
                .containsEntry("name", "Nightly").containsEntry("createdBy", null).containsEntry("startedAt", null)
                .containsEntry("projectId", CHECKOUT.toString()).containsEntry("tagExpression", null);
        assertThat(run.get("finishedAt")).isEqualTo(run.get("createdAt"));
        assertThat((String) JsonPath.read(body(result), "$.environment.name")).isEqualTo("staging");
        assertThat((Map<String, Object>) JsonPath.read(body(result), "$.build")).containsEntry("name", "1.4.2+517")
                .containsEntry("branch", "main").containsEntry("commitSha", "9fceb02").containsEntry("ciUrl", null);
        assertThat((Map<String, Object>) JsonPath.read(body(result), "$.summary.counts")).isEqualTo(Map.of(
                "PASSED", 2, "FAILED", 1, "PENDING", 1, "SKIPPED", 0, "UNDEFINED", 0, "AMBIGUOUS", 0, "UNKNOWN", 0));
        assertThat((Integer) JsonPath.read(body(result), "$.summary.total")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body(result), "$.summary.executed")).isEqualTo(4);
        assertThat(body(result)).contains("\"passRate\":50.0");
        Instant createdAt = Instant.parse((String) run.get("createdAt"));
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM upload_tokens", Instant.class)).isEqualTo(createdAt);
        assertThat(stub.received()).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/internal/service/projects/" + CHECKOUT);
            assertThat(call.serviceKey()).isEqualTo(TestSettings.INTERNAL_SERVICE_KEY);
            assertThat(call.requestId()).isEqualTo("up-1");
            assertThat(call.authorization()).isNull();
        });
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("AC-2.2 Results are unlinked snapshots in upload order, with their rows")
    void resultsAreSnapshotsInUploadOrder() throws Exception {
        assertThat(Uploads.upload(mockMvc, t1, "", CiSample.JSON).getResponse().getStatus()).isEqualTo(201);

        List<Map<String, Object>> results = jdbc.queryForList("SELECT * FROM test_case_results ORDER BY position");
        assertThat(results).extracting(row -> row.get("scenario_name"), row -> row.get("status")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Pay by card", "PASSED"),
                org.assertj.core.groups.Tuple.tuple("Pay with voucher SAVE10", "PASSED"),
                org.assertj.core.groups.Tuple.tuple("Pay with voucher OLD5", "FAILED"),
                org.assertj.core.groups.Tuple.tuple("Change address", "PENDING"));
        assertThat(results).allSatisfy(row -> {
            assertThat(row.get("feature_id")).isNull();
            assertThat(row.get("feature_version")).isNull();
            assertThat(row.get("scenario_id")).isNull();
            assertThat(row.get("example_row_id")).isNull();
            assertThat(row.get("attempt")).isEqualTo(1);
        });
        Map<String, Object> card = results.get(0);
        assertThat(card).containsEntry("feature_path", "features/checkout/payment.feature")
                .containsEntry("feature_name", "Payment").containsEntry("line", 12).containsEntry("duration_ms", 6L);
        assertThat(String.valueOf(card.get("tags"))).isEqualTo("[\"@checkout\", \"@smoke\"]");
        assertThat(jdbc.queryForList("SELECT kind, keyword_text, step_text, status, duration_ms FROM step_results "
                + "WHERE test_case_result_id = ? ORDER BY position", card.get("id"))).extracting(
                row -> row.get("kind"), row -> row.get("keyword_text"), row -> row.get("status"),
                row -> row.get("duration_ms")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("STEP", "Given", "PASSED", 1L),
                org.assertj.core.groups.Tuple.tuple("STEP", "When", "PASSED", 2L),
                org.assertj.core.groups.Tuple.tuple("STEP", "Then", "PASSED", 3L),
                org.assertj.core.groups.Tuple.tuple("AFTER_HOOK", null, "PASSED", 0L));
        List<Map<String, Object>> old5 = jdbc.queryForList("SELECT status, error_message FROM step_results "
                + "WHERE test_case_result_id = ? ORDER BY position", results.get(2).get("id"));
        assertThat(old5.get(1)).containsEntry("error_message", "expected rejected but was accepted");
        assertThat(old5.get(2)).containsEntry("status", "SKIPPED");
    }

    @Test
    @Tag("AC-2.6")
    @DisplayName("AC-2.6 No uploads into an archived project: 409, no run, lastUsedAt unchanged")
    void noUploadIntoAnArchivedProject() throws Exception {
        ReportsFixture.archive(CHECKOUT);

        assertProblem(Uploads.upload(mockMvc, t1, "", CiSample.JSON), 409, "PROJECT_ARCHIVED");
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM upload_tokens", Instant.class)).isNull();
    }

    @ParameterizedTest(name = "AC-2.8 {0}: {1} {2}, nothing stored, no C7 call")
    @CsvSource(delimiter = '|', value = {
            "the text \"not json\"                       | 400 | MALFORMED_REQUEST     | -",
            "{\"uri\":\"a.feature\"}                       | 422 | CUCUMBER_JSON_INVALID | $",
            "the CI sample with \"Passed\" as first status | 422 | CUCUMBER_JSON_INVALID "
                    + "| $[0].elements[0].steps[0].result.status",
            "[]                                         | 422 | UPLOAD_EMPTY          | -",
            "one feature with only a background         | 422 | UPLOAD_EMPTY          | -"})
    @Tag("AC-2.8")
    void invalidCucumberJsonStoresNothing(String row, int status, String code, String pointer) throws Exception {
        String body = switch (row.trim()) {
            case "the text \"not json\"" -> "not json";
            case "{\"uri\":\"a.feature\"}" -> "{\"uri\":\"a.feature\"}";
            case "the CI sample with \"Passed\" as first status" -> {
                ArrayNode sample = CiSample.tree();
                ((ObjectNode) sample.get(0).get("elements").get(0).get("steps").get(0).get("result"))
                        .put("status", "Passed");
                yield CiSample.json(sample);
            }
            case "[]" -> "[]";
            default -> "[{\"uri\":\"a.feature\",\"elements\":[{\"type\":\"background\",\"steps\":[]}]}]";
        };

        Map<String, Object> problem = assertProblem(Uploads.upload(mockMvc, t1, "", body), status, code);

        if (!pointer.equals("-")) {
            assertThat((List<?>) problem.get("errors")).singleElement()
                    .satisfies(error -> assertThat(((Map<?, ?>) error).get("pointer")).isEqualTo(pointer));
        }
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "AC-2.10 query {0}: 400 VALIDATION_FAILED for {1}")
    @CsvSource({
            "name=%20%20, name", "environment=<101 × e>, environment", "commit=xyz, commit",
            "ciUrl=ftp://ci.example.com/1, ciUrl", "ciUrl=not-a-url, ciUrl", "build=, build", "branch=a%07, branch",
            "name=<256 × n>, name"})
    @Tag("AC-2.10")
    void queryParametersAreValidated(String query, String field) throws Exception {
        String raw = query.replace("<101 × e>", "e".repeat(101)).replace("<256 × n>", "n".repeat(256));

        Map<String, Object> problem = assertProblem(Uploads.upload(mockMvc, t1, raw, CiSample.JSON), 400,
                "VALIDATION_FAILED");

        assertThat((List<?>) problem.get("errors")).singleElement()
                .satisfies(error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo(field));
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-2.10")
    @DisplayName("D13, BD-2: invalid query parameters (400) come before unreadable JSON (400) and 422")
    void queryParametersAreCheckedBeforeTheBody() throws Exception {
        Map<String, Object> problem = assertProblem(Uploads.upload(mockMvc, t1, "commit=xyz&ciUrl=x", "not json"),
                400, "VALIDATION_FAILED");

        List<Object> fields = ((List<?>) problem.get("errors")).stream()
                .map(error -> ((Map<?, ?>) error).get("field")).map(Object.class::cast).toList();
        assertThat(fields).containsExactly("ciUrl", "commit");
    }

    @Test
    @Tag("AC-2.11")
    @DisplayName("AC-2.11 Environments and builds are reused by exact name; the first build metadata wins")
    void environmentsAndBuildsAreReused() throws Exception {
        MvcResult first = Uploads.upload(mockMvc, t1, AC_2_1_QUERY, CiSample.JSON);
        MvcResult second = Uploads.upload(mockMvc, t1, "environment=staging&build=1.4.2%2B517&branch=release",
                CiSample.JSON);
        MvcResult third = Uploads.upload(mockMvc, t1, "environment=Staging", CiSample.JSON);

        assertThat((String) JsonPath.read(body(second), "$.environment.id"))
                .isEqualTo(JsonPath.read(body(first), "$.environment.id"));
        assertThat((String) JsonPath.read(body(second), "$.build.id"))
                .isEqualTo(JsonPath.read(body(first), "$.build.id"));
        assertThat((String) JsonPath.read(body(second), "$.build.branch")).isEqualTo("main");
        assertThat((String) JsonPath.read(body(third), "$.environment.id"))
                .isNotEqualTo(JsonPath.read(body(first), "$.environment.id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM builds", Integer.class)).isEqualTo(1);
    }

    @Test
    @Tag("AC-2.1")
    @DisplayName("D9: without a name, the run is called \"CI upload <createdAt>\"")
    void theDefaultNameIsCiUploadAndTheTime() throws Exception {
        MvcResult result = Uploads.upload(mockMvc, t1, "", CiSample.JSON);

        assertThat((String) JsonPath.read(body(result), "$.name"))
                .isEqualTo("CI upload " + JsonPath.read(body(result), "$.createdAt"));
        assertThat((Object) JsonPath.read(body(result), "$.environment")).isNull();
        assertThat((Object) JsonPath.read(body(result), "$.build")).isNull();
    }

    @Test
    @Tag("AC-2.12")
    @DisplayName("AC-2.12 Embeddings (2 MB) and match are ignored; error_message keeps 65,536 code points")
    void whatPhaseOneIgnoresOrShortens() throws Exception {
        ArrayNode sample = CiSample.tree();
        ObjectNode step = (ObjectNode) sample.get(0).get("elements").get(1).get("steps").get(0);
        step.putArray("embeddings").addObject().put("mime_type", "image/png").put("data", "A".repeat(2_000_000));
        step.putObject("match").put("location", "Steps.java:1");
        ((ObjectNode) step.get("result")).put("status", "failed").put("error_message", "x".repeat(70_000));

        assertThat(Uploads.upload(mockMvc, t1, "", CiSample.json(sample)).getResponse().getStatus()).isEqualTo(201);

        String stored = jdbc.queryForObject("SELECT error_message FROM step_results WHERE error_message LIKE 'xxx%'",
                String.class);
        assertThat(stored).hasSize(65_536);
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("AC-2.15 A Cucumber-JS 13 sample is stored with hook rows, table and doc string; no name is 422")
    void aCucumberJsSampleIsRead() throws Exception {
        String js = """
                [{"uri": "features/checkout/payment.feature", "name": "Payment", "elements": [
                  {"type": "scenario", "name": "Pay by card", "line": 8, "steps": [
                    {"hidden": true, "keyword": "Before", "result": {"status": "passed", "duration": 400000}},
                    {"arguments": [{"rows": [{"cells": ["item", "price"]}, {"cells": ["Mug", "8.00"]}]}],
                     "keyword": "Given ", "name": "a basket with these items:", "result": {"status": "passed"}},
                    {"arguments": [{"content": "Total: 8.00", "line": 15}],
                     "keyword": "Then ", "name": "the receipt is:", "result": {"status": "passed"}},
                    {"hidden": true, "keyword": "After", "result": {"status": "failed", "error_message": "boom"}}]}]}]
                """;

        assertThat(Uploads.upload(mockMvc, t1, "", js).getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT status FROM test_case_results", String.class)).isEqualTo("FAILED");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT kind, keyword_text, step_text, "
                + "data_table::text AS data_table, doc_string, error_message FROM step_results ORDER BY position");
        assertThat(rows).extracting(row -> row.get("kind")).containsExactly("BEFORE_HOOK", "STEP", "STEP",
                "AFTER_HOOK");
        assertThat(rows.get(0)).containsEntry("keyword_text", null).containsEntry("step_text", null);
        assertThat(rows.get(1)).containsEntry("keyword_text", "Given")
                .containsEntry("data_table", "[[\"item\", \"price\"], [\"Mug\", \"8.00\"]]");
        assertThat(rows.get(2)).containsEntry("doc_string", "Total: 8.00");
        assertThat(rows.get(3)).containsEntry("error_message", "boom");

        Map<String, Object> problem = assertProblem(Uploads.upload(mockMvc, t1, "",
                js.replace("\"name\": \"a basket with these items:\", ", "")), 422, "CUCUMBER_JSON_INVALID");
        assertThat((List<?>) problem.get("errors")).singleElement().satisfies(error -> assertThat(
                ((Map<?, ?>) error).get("pointer")).isEqualTo("$[0].elements[0].steps[1].name"));
    }
}
