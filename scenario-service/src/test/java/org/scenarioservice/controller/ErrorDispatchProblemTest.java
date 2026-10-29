package org.scenarioservice.controller;

import com.jayway.jsonpath.DocumentContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.HttpCalls;
import org.scenarioservice.support.HttpCalls.Answer;
import org.scenarioservice.support.RunningScenarioService;
import org.scenarioservice.support.TestTokens;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.ScenarioFixture.ANA;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;

/**
 * platform-hardening US-4 (SFU-2, D3, D4, D5): everything that reaches the container's error dispatch is a Problem of
 * this service's contract, and none of it calls the access check (C5). Proven against a running server on a random
 * port (D9), because MockMvc skips the error dispatch. The server is started once for this class and closed
 * afterwards, so it adds no cached test context.
 */
class ErrorDispatchProblemTest {

    private static final String REQUEST_ID = "ph-sc-1";
    private static final String FEATURES = "/api/projects/" + CHECKOUT + "/features";

    private static RunningScenarioService server;
    private static HttpCalls http;

    private final AccessCheckStub stub = AccessCheckStub.get();

    @BeforeAll
    static void start() throws SQLException {
        server = RunningScenarioService.start();
        http = server.http();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "AC-4.1 GET {0} with {1}: 400 MALFORMED_REQUEST Problem, no C5 call")
    @CsvSource({
            "/features;x=1, valid token",
            "//features, valid token",
            "/features/%2E%2E/{F}, valid token",
            "/features;x=1, no token"})
    @Tag("AC-4.1")
    void firewallRejectionsAreProblems(String suffix, String auth) throws Exception {
        String path = "/api/projects/" + CHECKOUT + suffix.replace("{F}", UUID.randomUUID().toString());

        Answer answer = auth.equals("valid token")
                ? http.send("GET", path, null, "X-Request-Id", REQUEST_ID, "Authorization", "Bearer " + anaToken())
                : http.send("GET", path, null, "X-Request-Id", REQUEST_ID);

        DocumentContext problem = answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(problem.read("$.detail", String.class)).isEqualTo("The request could not be read.");
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        assertThat(problem.read("$.instance", String.class)).as("the path as sent").isEqualTo(path);
        assertThat(answer.body()).doesNotContain(anaToken());
        assertThat(stub.received()).as("C5 requests").isEmpty();
    }

    @Test
    @Tag("AC-4.2")
    @DisplayName("AC-4.2 TRACE is a 405 METHOD_NOT_ALLOWED Problem that echoes nothing, no C5 call")
    void traceIsAProblemThatEchoesNothing() throws Exception {
        Answer answer = http.send("TRACE", FEATURES, null, "Authorization", "Bearer qa-trace-marker",
                "X-Request-Id", REQUEST_ID);

        DocumentContext problem = answer.assertProblem(405, "METHOD_NOT_ALLOWED");
        assertThat(problem.read("$.instance", String.class)).isEqualTo(FEATURES);
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        assertThat(answer.body()).doesNotContain("qa-trace-marker", "Authorization");
        assertThat(stub.received()).as("C5 requests").isEmpty();
    }

    @ParameterizedTest(name = "AC-4.3 GET /error with {0}: an unrouted path, no C5 call")
    @ValueSource(strings = {"no token", "an invalid token", "a valid token"})
    @Tag("AC-4.3")
    void aDirectErrorRequestIsAnUnroutedPath(String auth) throws Exception {
        Answer answer = switch (auth) {
            case "no token" -> http.send("GET", "/error", null);
            case "an invalid token" -> http.send("GET", "/error", null, "Authorization",
                    "Bearer " + TestTokens.signedWithAnotherSecret(ANA.id(), "ana@example.com"));
            default -> http.send("GET", "/error", null, "Authorization", "Bearer " + anaToken());
        };

        if (auth.equals("a valid token")) {
            DocumentContext problem = answer.assertProblem(404, "NOT_FOUND");
            assertThat(problem.read("$.detail", String.class)).isEqualTo("No resource matches this path.");
        } else {
            answer.assertProblem(401, "UNAUTHENTICATED");
            assertThat(answer.wwwAuthenticate()).hasValue("Bearer");
        }
        assertThat(stub.received()).as("C5 requests").isEmpty();
    }

    @ParameterizedTest(name = "AC-4.4 GET {0}: 400 from the container, no internals, no C5 call (D6)")
    @ValueSource(strings = {"/features/a%2Fb", "%00/features"})
    @Tag("AC-4.4")
    void containerRejectionsStayAsAccepted(String suffix) throws Exception {
        Answer answer = http.send("GET", "/api/projects/" + CHECKOUT + suffix, null,
                "Authorization", "Bearer " + anaToken());

        assertThat(answer.status()).isEqualTo(400);
        answer.assertNoInternals();
        assertThat(stub.received()).as("C5 requests").isEmpty();
    }

    private static String anaToken() {
        return TestTokens.valid(ANA.id(), "ana@example.com");
    }
}
