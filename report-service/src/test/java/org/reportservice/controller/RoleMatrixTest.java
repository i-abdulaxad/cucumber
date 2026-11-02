package org.reportservice.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.Plans;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.ReportsFixture.User;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.reportservice.support.ScenarioServiceStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.EVE;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.reportservice.support.ReportsFixture.IVY;
import static org.reportservice.support.ScenarioServiceStub.PAYMENT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-6.1 and AC-6.2 (D7, D13): each operation's minimum role on Checkout, and the archived project being read-only.
 */
@ReportServiceIntegrationTest
class RoleMatrixTest {

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

    @ParameterizedTest(name = "AC-6.1 {0} {1}: {2}")
    @CsvSource({
            "Dan, list runs, 200", "Dan, view run, 200", "Dan, list results, 200", "Hal, list runs, 200",
            "Dan, record a result, 403", "Hal, record a result, 200", "Hal, complete a run, 200",
            "Hal, plan a run, 403", "Hal, cancel a run, 403", "Gus, plan a run, 201", "Gus, cancel a run, 200",
            "Gus, create an upload token, 403", "Cai, create an upload token, 201", "Ivy, list runs, 403",
            "Eve, view run, 403", "Ivy, plan a run, 403"})
    @Tag("AC-6.1")
    void roleTimesOperation(String user, String operation, int status) throws Exception {
        MvcResult result = perform(user(user), operation);

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(status);
        if (status == 403) {
            assertProblem(result, 403, "FORBIDDEN");
        }
    }

    @ParameterizedTest(name = "AC-6.2 archived Checkout: {0} {1}: {2}")
    @CsvSource({"Dan, view run, 200", "Gus, plan a run, 409", "Hal, record a result, 409", "Hal, complete a run, 409",
            "Gus, cancel a run, 409"})
    @Tag("AC-6.2")
    void anArchivedProjectIsReadOnly(String user, String operation, int status) throws Exception {
        ReportsFixture.archive(CHECKOUT);

        MvcResult result = perform(user(user), operation);

        if (status == 409) {
            assertProblem(result, 409, "PROJECT_ARCHIVED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE status <> 'PLANNED'",
                    Integer.class)).isZero();
        } else {
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(status);
        }
    }

    private MvcResult perform(User user, String operation) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        return switch (operation) {
            case "list runs" -> Runs.read(mockMvc, user, CHECKOUT, "");
            case "view run" -> Runs.read(mockMvc, user, CHECKOUT, "/" + run.id());
            case "list results" -> Runs.read(mockMvc, user, CHECKOUT, "/" + run.id() + "/results");
            case "record a result" -> Runs.record(mockMvc, user, CHECKOUT, run.id(), run.results().get(0),
                    "{\"status\":\"PASSED\"}");
            case "complete a run" -> Runs.complete(mockMvc, user, CHECKOUT, run.id());
            case "cancel a run" -> Runs.cancel(mockMvc, user, CHECKOUT, run.id());
            case "plan a run" -> Plans.plan(mockMvc, user, CHECKOUT, Plans.body("R", "", PAYMENT));
            default -> mockMvc.perform(post("/api/projects/" + CHECKOUT + "/upload-tokens")
                    .header(HttpHeaders.AUTHORIZATION, user.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"CI\"}")).andReturn();
        };
    }

    private static User user(String name) {
        return switch (name) {
            case "Dan" -> DAN;
            case "Hal" -> HAL;
            case "Gus" -> GUS;
            case "Cai" -> CAI;
            case "Ivy" -> IVY;
            default -> EVE;
        };
    }
}
