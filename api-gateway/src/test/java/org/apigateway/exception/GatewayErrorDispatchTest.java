package org.apigateway.exception;

import com.jayway.jsonpath.DocumentContext;
import org.apigateway.filter.AccessLogFilter;
import org.apigateway.support.GatewayLauncher;
import org.apigateway.support.HttpCalls;
import org.apigateway.support.HttpCalls.Answer;
import org.apigateway.support.StubServer;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * platform-hardening US-5 (FU-3, FU-1 {@code /error}; D3, D4, D5): the gateway's own rejections are Problems in its
 * D6 format (no {@code code}), and none is forwarded. Proven against a running gateway on a random port of
 * {@code 127.0.0.1} (D9), started once for this class with its own stubs and closed afterwards, so it adds no cached
 * test context.
 */
@ExtendWith(OutputCaptureExtension.class)
class GatewayErrorDispatchTest {

    private static final String URL_REJECTED = "The request URL was rejected.";
    private static final String METHOD_NOT_SUPPORTED = "This method is not supported for this path.";

    private static StubServer authService;
    private static StubServer scenarioService;
    private static StubServer reportService;
    private static ConfigurableApplicationContext gateway;
    private static HttpCalls http;

    @BeforeAll
    static void start() {
        authService = StubServer.start("auth-service");
        scenarioService = StubServer.start("scenario-service");
        reportService = StubServer.start("report-service");
        Map<String, Object> environment = GatewayLauncher.testEnvironment();
        environment.put("SERVER_ADDRESS", "127.0.0.1");
        environment.put("AUTH_SERVICE_URL", authService.baseUrl());
        environment.put("SCENARIO_SERVICE_URL", scenarioService.baseUrl());
        environment.put("REPORT_SERVICE_URL", reportService.baseUrl());
        gateway = GatewayLauncher.run(environment);
        http = new HttpCalls(Integer.parseInt(gateway.getEnvironment().getProperty("local.server.port")));
    }

    @AfterAll
    static void stop() {
        if (gateway != null) {
            gateway.close();
        }
        for (StubServer stub : new StubServer[] {authService, scenarioService, reportService}) {
            if (stub != null) {
                stub.close();
            }
        }
    }

    @BeforeEach
    void resetStubs() {
        authService.reset();
        scenarioService.reset();
        reportService.reset();
    }

    @ParameterizedTest(name = "AC-5.1 {0} {1} with {2}: 400 D6 Problem, not forwarded, one access entry")
    @CsvSource({
            "POST, /api/auth/./login, no token",
            "GET, /api/orgs//x, valid token",
            "POST, /api;x=1/auth/invitations/inv-QaSemi4Secret/accept, no token"})
    @Tag("AC-5.1")
    void firewallRejectionsAreD6Problems(String method, String path, String auth, CapturedOutput output)
            throws Exception {
        String body = method.equals("POST") ? "{}" : null;

        Answer answer = auth.equals("valid token")
                ? http.send(method, path, body, "Authorization", TestTokens.bearerValid())
                : http.send(method, path, body);

        assertThat(answer.requestIdHeader()).as("X-Request-Id response header").isPresent();
        DocumentContext problem = answer.assertProblem(400, URL_REJECTED);
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(answer.requestIdHeader().get());
        assertThat(answer.body()).doesNotContain(TestTokens.valid());
        assertNothingForwarded();
        assertExactlyOneAccessEntry(output, answer.requestIdHeader().get());
    }

    @Test
    @Tag("AC-5.2")
    @DisplayName("AC-5.2 POST /actuator/health is a 405 D6 Problem with the D5 detail and the requestId")
    void anUnsupportedMethodIsAD6Problem() throws Exception {
        Answer answer = http.send("POST", "/actuator/health", null, "Authorization", TestTokens.bearerValid());

        assertThat(answer.requestIdHeader()).as("X-Request-Id response header").isPresent();
        answer.assertProblem(405, METHOD_NOT_SUPPORTED);
        assertNothingForwarded();
    }

    @Test
    @Tag("AC-5.3")
    @DisplayName("AC-5.3 TRACE is a 405 D6 Problem that echoes nothing and isn't forwarded")
    void traceIsAProblemThatEchoesNothing() throws Exception {
        Answer answer = http.send("TRACE", "/api/orgs", null, "Authorization", "Bearer qa-trace-marker");

        answer.assertProblem(405, METHOD_NOT_SUPPORTED);
        assertThat(answer.body()).doesNotContain("qa-trace-marker", "Authorization");
        assertNothingForwarded();
    }

    @ParameterizedTest(name = "AC-5.4 GET /error with {0}: an unrouted path")
    @ValueSource(strings = {"no token", "a valid token"})
    @Tag("AC-5.4")
    void aDirectErrorRequestIsAnUnroutedPath(String auth) throws Exception {
        if (auth.equals("no token")) {
            http.send("GET", "/error", null).assertProblem(401, "A valid bearer token is required.");
        } else {
            http.send("GET", "/error", null, "Authorization", TestTokens.bearerValid())
                    .assertProblem(404, "No API route matches this path.");
        }
        assertNothingForwarded();
    }

    @Test
    @Tag("AC-5.5")
    @DisplayName("AC-5.5 container-level rejections stay as accepted and are never forwarded (D6)")
    void containerRejectionsStayAsAccepted() throws Exception {
        assertThat(http.send("POST", "/api/auth/login%2F..%2Forgs", "{}", "Authorization", TestTokens.bearerValid())
                .status()).isEqualTo(400);
        assertThat(http.sendRaw("CONNECT", "/api/orgs", "Authorization", TestTokens.bearerValid())).isEqualTo(501);
        assertNothingForwarded();
    }

    private static void assertNothingForwarded() {
        for (StubServer stub : List.of(authService, scenarioService, reportService)) {
            assertThat(stub.requests()).as("requests received by %s", stub.name()).isEmpty();
        }
    }

    /** The access entry is written after the response is sent: waits for it, then checks there is only one. */
    private static void assertExactlyOneAccessEntry(CapturedOutput output, String requestId) {
        Pattern entry = Pattern.compile(Pattern.quote(AccessLogFilter.LOGGER_NAME)
                + ".*requestId=" + Pattern.quote(requestId) + "(\\s|$)");
        await().atMost(Duration.ofSeconds(5))
                .until(() -> output.getOut().lines().anyMatch(line -> entry.matcher(line).find()));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> output.getOut().lines().filter(line -> entry.matcher(line).find()).count() == 1);
    }
}
