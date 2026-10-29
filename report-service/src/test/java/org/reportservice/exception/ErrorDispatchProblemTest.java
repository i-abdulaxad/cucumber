package org.reportservice.exception;

import com.jayway.jsonpath.DocumentContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.HttpCalls;
import org.reportservice.support.HttpCalls.Answer;
import org.reportservice.support.RunningReportService;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;

/**
 * D13 (as scenario-service, platform-hardening D3 to D5): everything that reaches the container's error dispatch is a
 * Problem of this service's contract, and none of it calls C5. Proven against a running server on {@code 127.0.0.1}
 * (MockMvc skips the error dispatch), started once for the class, so it adds no cached test context.
 */
class ErrorDispatchProblemTest {

    private static final String REQUEST_ID = "rs-ed-1";
    private static final String PROBE = "/api/projects/" + CHECKOUT + "/probe";

    private static RunningReportService server;
    private static HttpCalls http;

    private final AuthServiceStub stub = AuthServiceStub.get();

    @BeforeAll
    static void start() throws SQLException {
        server = RunningReportService.start();
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

    @ParameterizedTest(name = "D13 GET {0}: a 400 MALFORMED_REQUEST Problem from the firewall, no C5 call")
    @ValueSource(strings = {";x=1", "/%2E%2E/x"})
    void firewallRejectionsAreProblems(String suffix) throws Exception {
        String path = PROBE + suffix;

        Answer answer = http.send("GET", path, null, "X-Request-Id", REQUEST_ID, "Authorization", DAN.bearer());

        DocumentContext problem = answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        assertThat(answer.body()).doesNotContain(DAN.token());
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @DisplayName("D13 TRACE is a 405 METHOD_NOT_ALLOWED Problem that echoes nothing, no C5 call")
    void traceIsAProblemThatEchoesNothing() throws Exception {
        Answer answer = http.send("TRACE", PROBE, null, "Authorization", "Bearer qa-trace-marker",
                "X-Request-Id", REQUEST_ID);

        answer.assertProblem(405, "METHOD_NOT_ALLOWED");
        assertThat(answer.body()).doesNotContain("qa-trace-marker", "Authorization");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "D13 GET /error with {0}: an unrouted path, no C5 call")
    @ValueSource(strings = {"no token", "a valid token"})
    void aDirectErrorRequestIsAnUnroutedPath(String auth) throws Exception {
        Answer answer = auth.equals("no token")
                ? http.send("GET", "/error", null)
                : http.send("GET", "/error", null, "Authorization", DAN.bearer());

        if (auth.equals("no token")) {
            answer.assertProblem(401, "UNAUTHENTICATED");
            assertThat(answer.wwwAuthenticate()).hasValue("Bearer");
        } else {
            answer.assertProblem(404, "NOT_FOUND");
        }
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @DisplayName("D5 On a real server, POST /api/ci/runs with a user JWT is 401 UPLOAD_TOKEN_INVALID, no C5 call")
    void theCiPathRefusesAUserJwt() throws Exception {
        Answer answer = http.send("POST", "/api/ci/runs", "[]", "Authorization", DAN.bearer());

        answer.assertProblem(401, "UPLOAD_TOKEN_INVALID");
        assertThat(answer.wwwAuthenticate()).hasValue("Bearer");
        assertThat(stub.received()).isEmpty();
    }
}
