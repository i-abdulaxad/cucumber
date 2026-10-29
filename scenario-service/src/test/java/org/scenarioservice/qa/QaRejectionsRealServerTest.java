package org.scenarioservice.qa;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.ScenarioFixture.ANA;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;

/**
 * QA adversarial checks of platform-hardening US-4 (D3, D4, D5) on a running scenario-service (D9): more firewall
 * forms than the AC rows, content negotiation, TRACE with a cookie and custom headers, and direct {@code /error}
 * variants. None of them may reach the access check (C5). The server is started once and closed afterwards
 * ({@link RunningScenarioService}), so no cached test context is added.
 */
@DisplayName("QA platform-hardening US-4: scenario-service rejections are Problems, never an access check")
class QaRejectionsRealServerTest {

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

    @ParameterizedTest(name = "GET {0} ({1})")
    @CsvSource({
            "/features;jsessionid=abc, valid token",
            "/features/%2e%2e/x,       valid token",
            "/./features,              valid token",
            "/features/%252e%252e,     valid token",
            "/features/%3Bx,           valid token",
            "/features/..;/x,          valid token",
            "/features/%2e%2e/x,       expired token"})
    @Tag("AC-4.1")
    @DisplayName("AC-4.1 (more rows): firewall rejections are 400 MALFORMED_REQUEST Problems before any access check")
    void moreFirewallFormsAreProblems(String suffix, String auth) throws Exception {
        String path = "/api/projects/" + CHECKOUT + suffix;
        String token = auth.equals("valid token") ? TestTokens.valid(ANA.id(), "ana@example.com")
                : TestTokens.expired(ANA.id(), "ana@example.com");

        Answer answer = http.send("GET", path, null, "Authorization", "Bearer " + token, "X-Request-Id", "qa-ph-sc-2");

        DocumentContext problem = answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(problem.read("$.instance", String.class)).isEqualTo(path);
        assertThat(problem.read("$.requestId", String.class)).isEqualTo("qa-ph-sc-2");
        assertThat(answer.body()).doesNotContain(token);
        assertThat(stub.received()).as("C5 requests").isEmpty();
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/html", "application/xml", "text/plain"})
    @Tag("AC-4.1")
    @DisplayName("D3: a firewall rejection is the 400 Problem whatever the client accepts")
    void theProblemIgnoresTheAcceptHeader(String accept) throws Exception {
        Answer answer = http.send("GET", FEATURES + ";x=1", null, "Accept", accept);

        answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-4.2")
    @DisplayName("AC-4.2 (more): TRACE with a valid token, a cookie and a custom header echoes none of them")
    void traceEchoesNothing() throws Exception {
        String token = TestTokens.valid(ANA.id(), "ana@example.com");

        Answer answer = http.send("TRACE", FEATURES, null, "Authorization", "Bearer " + token,
                "Cookie", "SESSION=qa-cookie-marker", "X-Qa-Marker", "qa-header-marker");

        DocumentContext problem = answer.assertProblem(405, "METHOD_NOT_ALLOWED");
        assertThat(problem.read("$.instance", String.class)).isEqualTo(FEATURES);
        assertThat(answer.body()).doesNotContain(token, "qa-cookie-marker", "qa-header-marker", "Cookie");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST,   /error,            valid token,   404",
            "DELETE, /error,            valid token,   404",
            "GET,    /error?status=500, valid token,   404",
            "GET,    /error,            expired token, 401",
            "POST,   /error,            no token,      401",
            "GET,    /error;x=1,        valid token,   400"})
    @Tag("AC-4.3")
    @DisplayName("AC-4.3 (more rows): a direct /error stays an unrouted path whatever the method, query or token")
    void directErrorVariants(String method, String target, String auth, int status) throws Exception {
        Answer answer = switch (auth) {
            case "valid token" -> http.send(method, target, null, "Authorization",
                    "Bearer " + TestTokens.valid(ANA.id(), "ana@example.com"));
            case "expired token" -> http.send(method, target, null, "Authorization",
                    "Bearer " + TestTokens.expired(ANA.id(), "ana@example.com"));
            default -> http.send(method, target, null);
        };

        switch (status) {
            case 404 -> answer.assertProblem(404, "NOT_FOUND");
            case 401 -> {
                answer.assertProblem(401, "UNAUTHENTICATED");
                assertThat(answer.wwwAuthenticate()).hasValue("Bearer");
            }
            default -> answer.assertProblem(400, "MALFORMED_REQUEST");
        }
        assertThat(stub.received()).as("C5 requests").isEmpty();
    }
}
