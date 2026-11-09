package org.reportservice.qa;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * qa, IDOR and cross-project isolation (AC-4.7, D13 "a run, result or token of another project is a 404", D7 "no
 * access and an unknown project are both 403"). Each case asserts the foreign object is unchanged.
 */
@ReportServiceIntegrationTest
class QaCrossProjectTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private RunData.Run billingRun;
    private RunData.Run runA;
    private RunData.Run runB;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ScenarioServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        billingRun = RunData.manual(jdbc, BILLING, "PLANNED", 1);
        runA = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        runB = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
    }

    @ParameterizedTest(name = "AC-4.7 qa: Ana {0} a Billing run through Checkout's path -> 404 RUN_NOT_FOUND")
    @CsvSource({"view", "results", "result", "record", "complete", "cancel"})
    @Tag("AC-4.7")
    void aBillingRunThroughCheckoutsPath(String operation) throws Exception {
        UUID run = billingRun.id();
        UUID result = billingRun.results().get(0);
        MvcResult answer = switch (operation) {
            case "view" -> Runs.read(mockMvc, ANA, CHECKOUT, "/" + run);
            case "results" -> Runs.read(mockMvc, ANA, CHECKOUT, "/" + run + "/results");
            case "result" -> Runs.read(mockMvc, ANA, CHECKOUT, "/" + run + "/results/" + result);
            case "record" -> Runs.record(mockMvc, ANA, CHECKOUT, run, result, "{\"status\":\"PASSED\"}");
            case "complete" -> Runs.complete(mockMvc, ANA, CHECKOUT, run);
            default -> Runs.cancel(mockMvc, ANA, CHECKOUT, run);
        };

        assertProblem(answer, 404, "RUN_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM test_runs WHERE id = ?", String.class, run))
                .isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT status FROM test_case_results WHERE id = ?", String.class, result))
                .isEqualTo("UNKNOWN");
    }

    @Test
    @Tag("AC-4.7")
    @DisplayName("AC-4.7 qa: run A's path with run B's result is 404 RESULT_NOT_FOUND for view and record; B unchanged")
    void aResultOfAnotherRun() throws Exception {
        UUID foreign = runB.results().get(0);
        assertProblem(Runs.read(mockMvc, DAN, CHECKOUT, "/" + runA.id() + "/results/" + foreign), 404,
                "RESULT_NOT_FOUND");
        assertProblem(Runs.record(mockMvc, HAL, CHECKOUT, runA.id(), foreign, "{\"status\":\"FAILED\"}"), 404,
                "RESULT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM test_case_results WHERE id = ?", String.class, foreign))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE status <> 'PLANNED'", Integer.class))
                .isZero();
    }

    @Test
    @Tag("AC-6.1")
    @DisplayName("D7 qa: Cai on a real Billing run gets the same 403 body as on an unknown project (no existence leak)")
    void noExistenceLeak() throws Exception {
        MvcResult real = Runs.read(mockMvc, CAI, BILLING, "/" + billingRun.id());
        UUID unknownProject = UUID.fromString("00000000-0000-4000-8000-00000000dead");
        MvcResult unknown = Runs.read(mockMvc, CAI, unknownProject, "/" + billingRun.id());

        Map<String, Object> first = assertProblem(real, 403, "FORBIDDEN");
        Map<String, Object> second = assertProblem(unknown, 403, "FORBIDDEN");
        first.remove("instance");
        second.remove("instance");
        assertThat(first).isEqualTo(second);
        assertProblem(Runs.read(mockMvc, CAI, unknownProject, ""), 403, "FORBIDDEN");
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("AC-2.4 qa: a Billing upload is invisible on Checkout's path and visible to Ana on Billing's")
    void uploadsStayInTheirProject() throws Exception {
        String b1 = Uploads.activeToken(jdbc, BILLING);
        MvcResult upload = Uploads.upload(mockMvc, b1, "", CiSample.JSON);
        assertThat(upload.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(body(upload), "$.id");

        assertProblem(Runs.read(mockMvc, ANA, CHECKOUT, "/" + id), 404, "RUN_NOT_FOUND");
        assertThat(Runs.read(mockMvc, ANA, BILLING, "/" + id).getResponse().getStatus()).isEqualTo(200);
        assertThat(body(Runs.read(mockMvc, DAN, CHECKOUT, ""))).doesNotContain(id);
    }

    @Test
    @Tag("AC-6.1")
    @DisplayName("D7 qa: X-User-Id can't make Dan a tester; the result keeps no executedBy")
    void headersDontRaiseRoles() throws Exception {
        MvcResult result = mockMvc.perform(put(Plans.runs(CHECKOUT) + "/" + runA.id() + "/results/"
                + runA.results().get(0)).header(HttpHeaders.AUTHORIZATION, DAN.bearer())
                .header("X-User-Id", HAL.id().toString()).header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PASSED\"}")).andReturn();
        assertProblem(result, 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results WHERE executed_by IS NOT NULL",
                Integer.class)).isZero();
    }

    @ParameterizedTest(name = "AC-6.5 qa: {0} with an upper-case id -> 400 MALFORMED_REQUEST, C5 not called")
    @CsvSource({"record run id", "record result id", "complete", "cancel"})
    @Tag("AC-6.5")
    void strictIdsOnWrites(String operation) throws Exception {
        String run = runA.id().toString();
        String result = runA.results().get(0).toString();
        MvcResult answer = switch (operation) {
            case "record run id" -> mockMvc.perform(put(Plans.runs(CHECKOUT) + "/" + run.toUpperCase() + "/results/"
                    + result).header(HttpHeaders.AUTHORIZATION, HAL.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"PASSED\"}")).andReturn();
            case "record result id" -> mockMvc.perform(put(Plans.runs(CHECKOUT) + "/" + run + "/results/"
                    + result.toUpperCase()).header(HttpHeaders.AUTHORIZATION, HAL.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PASSED\"}")).andReturn();
            default -> mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post(Plans.runs(CHECKOUT) + "/" + run.toUpperCase() + "/" + operation)
                    .header(HttpHeaders.AUTHORIZATION, HAL.bearer())).andReturn();
        };
        assertProblem(answer, 400, "MALFORMED_REQUEST");
        assertThat(AuthServiceStub.get().received()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE status <> 'PLANNED'", Integer.class))
                .isZero();
    }
}
