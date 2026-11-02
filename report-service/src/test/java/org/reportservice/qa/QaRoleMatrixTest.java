package org.reportservice.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.EVE;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.reportservice.support.ReportsFixture.IVY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, the full D7 matrix (AC-6.1, AC-6.2, AC-1.6 extended): 7 users × 11 operations on active Checkout, each on fresh
 * data. Every 403 asserts the database is unchanged, C5 was asked exactly once, and scenario-service never. Archived
 * Checkout: writes at or above the minimum role are 409 PROJECT_ARCHIVED, below it 403, token list and revoke and all
 * reads stay 200.
 */
@ReportServiceIntegrationTest
class QaRoleMatrixTest {

    private static final List<String> OPERATIONS = List.of("create token", "list tokens", "revoke token", "plan",
            "cancel", "record", "complete", "list runs", "view run", "list results", "view result");
    private static final Map<String, String> MINIMUM = Map.ofEntries(Map.entry("create token", "ADMIN"),
            Map.entry("list tokens", "ADMIN"), Map.entry("revoke token", "ADMIN"), Map.entry("plan", "MEMBER"),
            Map.entry("cancel", "MEMBER"), Map.entry("record", "TESTER"), Map.entry("complete", "TESTER"),
            Map.entry("list runs", "READER"), Map.entry("view run", "READER"), Map.entry("list results", "READER"),
            Map.entry("view result", "READER"));
    private static final List<String> RANKS = List.of("READER", "TESTER", "MEMBER", "ADMIN");
    private static final Map<String, User> USERS = Map.of("Ana", ANA, "Cai", CAI, "Gus", GUS, "Hal", HAL, "Dan", DAN,
            "Ivy", IVY, "Eve", EVE);
    private static final Map<String, String> ROLES = Map.of("Ana", "ADMIN", "Cai", "ADMIN", "Gus", "MEMBER",
            "Hal", "TESTER", "Dan", "READER");

    private final AuthServiceStub auth = AuthServiceStub.get();
    private final ScenarioServiceStub scenarios = ScenarioServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private RunData.Run run;
    private UUID tokenId;

    @BeforeEach
    void resetData() {
        auth.reset();
        scenarios.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 2);
        tokenId = ReportData.token(jdbc, CHECKOUT, "T1", ReportData.randomToken(), CAI.id(), Instant.now(), null, null);
        auth.reset();
        scenarios.reset();
    }

    @AfterEach
    void unarchive() {
        ReportsFixture.reset();
    }

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (String user : List.of("Ana", "Cai", "Gus", "Hal", "Dan", "Ivy", "Eve")) {
            for (String operation : OPERATIONS) {
                cells.add(Arguments.of(user, operation));
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "AC-6.1 qa: {0} {1}")
    @MethodSource("cells")
    @Tag("AC-6.1")
    void activeProject(String user, String operation) throws Exception {
        boolean allowed = allowed(user, operation);

        MvcResult result = perform(USERS.get(user), operation);

        if (allowed) {
            int expected = operation.equals("plan") || operation.equals("create token") ? 201 : 200;
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(expected);
        } else {
            assertProblem(result, 403, "FORBIDDEN");
            assertUnchanged();
            assertThat(auth.received()).filteredOn(call -> call.path().endsWith("/access")).hasSize(1);
            assertThat(scenarios.received()).isEmpty();
        }
    }

    @ParameterizedTest(name = "AC-6.2 qa: archived, {0} {1}")
    @MethodSource("cells")
    @Tag("AC-6.2")
    void archivedProject(String user, String operation) throws Exception {
        ReportsFixture.archive(CHECKOUT);
        boolean allowed = allowed(user, operation);
        boolean readOnlyAllowed = operation.startsWith("list") || operation.startsWith("view")
                || operation.equals("revoke token");

        MvcResult result = perform(USERS.get(user), operation);

        if (!allowed) {
            assertProblem(result, 403, "FORBIDDEN");
            assertUnchanged();
        } else if (readOnlyAllowed) {
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        } else {
            assertProblem(result, 409, "PROJECT_ARCHIVED");
            assertUnchanged();
            assertThat(scenarios.received()).isEmpty();
        }
    }

    @ParameterizedTest(name = "AC-6.1 qa: Bearer upload token on {0} -> 401 UNAUTHENTICATED, C5 not called")
    @CsvSource({"list runs", "view run", "list results", "view result", "plan", "record", "complete", "cancel"})
    @Tag("AC-2.5")
    void anUploadTokenIsNoUserCredential(String operation) throws Exception {
        String token = ReportData.randomToken();
        ReportData.token(jdbc, CHECKOUT, "ci", token, CAI.id(), Instant.now(), null, null);
        String path = Plans.runs(CHECKOUT) + switch (operation) {
            case "list runs", "plan" -> "";
            case "view run" -> "/" + run.id();
            case "list results" -> "/" + run.id() + "/results";
            case "view result", "record" -> "/" + run.id() + "/results/" + run.results().get(0);
            default -> "/" + run.id() + "/" + operation;
        };
        var request = switch (operation) {
            case "plan", "complete", "cancel" -> post(path);
            case "record" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
            default -> get(path);
        };
        MvcResult result = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(operation.equals("record")
                        ? "{\"status\":\"PASSED\"}" : Plans.body("R", "", ScenarioServiceStub.PAYMENT))).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(auth.received()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results WHERE status <> 'UNKNOWN'",
                Integer.class)).isZero();
    }

    private static boolean allowed(String user, String operation) {
        String role = ROLES.get(user);
        return role != null && RANKS.indexOf(role) >= RANKS.indexOf(MINIMUM.get(operation));
    }

    private MvcResult perform(User caller, String operation) throws Exception {
        String tokens = "/api/projects/" + CHECKOUT + "/upload-tokens";
        UUID result = run.results().get(0);
        return switch (operation) {
            case "create token" -> mockMvc.perform(post(tokens).header(HttpHeaders.AUTHORIZATION, caller.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andReturn();
            case "list tokens" -> mockMvc.perform(get(tokens).header(HttpHeaders.AUTHORIZATION, caller.bearer()))
                    .andReturn();
            case "revoke token" -> mockMvc.perform(post(tokens + "/" + tokenId + "/revoke")
                    .header(HttpHeaders.AUTHORIZATION, caller.bearer())).andReturn();
            case "plan" -> Plans.plan(mockMvc, caller, CHECKOUT, Plans.body("R", "", ScenarioServiceStub.PAYMENT));
            case "cancel" -> Runs.cancel(mockMvc, caller, CHECKOUT, run.id());
            case "record" -> Runs.record(mockMvc, caller, CHECKOUT, run.id(), result, "{\"status\":\"PASSED\"}");
            case "complete" -> Runs.complete(mockMvc, caller, CHECKOUT, run.id());
            case "list runs" -> Runs.read(mockMvc, caller, CHECKOUT, "");
            case "view run" -> Runs.read(mockMvc, caller, CHECKOUT, "/" + run.id());
            case "list results" -> Runs.read(mockMvc, caller, CHECKOUT, "/" + run.id() + "/results");
            default -> Runs.read(mockMvc, caller, CHECKOUT, "/" + run.id() + "/results/" + result);
        };
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results WHERE status <> 'UNKNOWN' "
                + "OR executed_by IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens WHERE revoked_at IS NOT NULL",
                Integer.class)).isZero();
    }
}
