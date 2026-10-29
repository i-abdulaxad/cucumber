package com.example.authservice.controller;

import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.HttpCalls;
import com.example.authservice.support.HttpCalls.Answer;
import com.example.authservice.support.RunningAuthService;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening US-2 (FU-1, D3, D4, D5): everything that reaches the container's error dispatch is a Problem of
 * this service's contract. Proven against a running server on a random port (D9), because MockMvc skips the error
 * dispatch. The server is started once for this class and closed afterwards, so it adds no cached test context.
 */
class ErrorDispatchProblemTest {

    private static final String REQUEST_ID = "ph-auth-1";
    private static final String REGISTRATION = "{\"email\":\"ph-zed@example.com\",\"password\":\"Secret123!\","
            + "\"fullName\":\"Zed Ph\"}";

    private static RunningAuthService server;
    private static HttpCalls http;
    private static String anaToken;

    @BeforeAll
    static void start() throws Exception {
        server = RunningAuthService.start();
        http = server.http();
        assertThat(http.send("POST", "/api/auth/register", "{\"email\":\"ana@example.com\","
                + "\"password\":\"Secret123!\",\"fullName\":\"Ana Lima\"}").status()).isEqualTo(201);
        Answer login = http.send("POST", "/api/auth/login",
                "{\"email\":\"ana@example.com\",\"password\":\"Secret123!\"}");
        assertThat(login.status()).isEqualTo(200);
        anaToken = JsonPath.read(login.body(), "$.accessToken");
    }

    @AfterAll
    static void stop() throws SQLException {
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest(name = "AC-2.1 {0} {1} with {2}: 400 MALFORMED_REQUEST Problem")
    @CsvSource({
            "POST, /api/auth/register;x=1, no token",
            "POST, /api/auth/./register, no token",
            "GET, /api/orgs//x, valid token",
            "GET, /api/orgs/%2E%2E/orgs, valid token"})
    @Tag("AC-2.1")
    void firewallRejectionsAreProblems(String method, String path, String auth) throws Exception {
        UserRepository userRepository = server.bean(UserRepository.class);
        OrganizationRepository organizationRepository = server.bean(OrganizationRepository.class);
        long users = userRepository.count();
        long organizations = organizationRepository.count();
        String body = method.equals("POST") ? REGISTRATION : null;

        Answer answer = auth.equals("valid token")
                ? http.send(method, path, body, "X-Request-Id", REQUEST_ID, "Authorization", "Bearer " + anaToken)
                : http.send(method, path, body, "X-Request-Id", REQUEST_ID);

        DocumentContext problem = answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(problem.read("$.detail", String.class)).isEqualTo("The request could not be read.");
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        assertThat(problem.read("$.instance", String.class)).as("the path as sent").isEqualTo(path);
        assertThat(answer.body()).doesNotContain(anaToken);
        assertThat(userRepository.count()).as("no user created").isEqualTo(users);
        assertThat(organizationRepository.count()).as("no organization created").isEqualTo(organizations);
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("AC-2.2 TRACE is a 405 METHOD_NOT_ALLOWED Problem that echoes nothing")
    void traceIsAProblemThatEchoesNothing() throws Exception {
        Answer answer = http.send("TRACE", "/api/orgs", null, "Authorization", "Bearer qa-trace-marker",
                "X-Request-Id", REQUEST_ID);

        DocumentContext problem = answer.assertProblem(405, "METHOD_NOT_ALLOWED");
        assertThat(problem.read("$.instance", String.class)).isEqualTo("/api/orgs");
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        assertThat(answer.body()).doesNotContain("qa-trace-marker", "Authorization");
    }

    @ParameterizedTest(name = "AC-2.3 GET /error with {0}: an unrouted path")
    @ValueSource(strings = {"no token", "an invalid token", "a valid token"})
    @Tag("AC-2.3")
    void aDirectErrorRequestIsAnUnroutedPath(String auth) throws Exception {
        Answer answer = switch (auth) {
            case "no token" -> http.send("GET", "/error", null);
            case "an invalid token" -> http.send("GET", "/error", null, "Authorization",
                    "Bearer " + TestJwt.signedWithAnotherSecret(AcmeFixture.UNKNOWN_ID, "nobody@example.com"));
            default -> http.send("GET", "/error", null, "Authorization", "Bearer " + anaToken);
        };

        if (auth.equals("a valid token")) {
            DocumentContext problem = answer.assertProblem(404, "NOT_FOUND");
            assertThat(problem.read("$.detail", String.class)).isEqualTo("No resource matches this path.");
        } else {
            answer.assertProblem(401, "UNAUTHENTICATED");
            assertThat(answer.wwwAuthenticate()).hasValue("Bearer");
        }
    }

    @ParameterizedTest(name = "AC-2.4 GET {0}: 400 from the container, no internals (D6)")
    @ValueSource(strings = {"/api/auth/invitations/a%2Fb/accept", "/api/orgs/%00"})
    @Tag("AC-2.4")
    void containerRejectionsStayAsAccepted(String path) throws Exception {
        Answer answer = http.send("GET", path, null);

        assertThat(answer.status()).isEqualTo(400);
        answer.assertNoInternals();
    }

    @Test
    @Tag("AC-2.5")
    @DisplayName("AC-2.5 existing Problems are unchanged: GET /api/orgs without a token is 401 UNAUTHENTICATED")
    void existingProblemsAreUnchanged() throws Exception {
        Answer answer = http.send("GET", "/api/orgs", null);

        DocumentContext problem = answer.assertProblem(401, "UNAUTHENTICATED");
        assertThat(problem.read("$.instance", String.class)).isEqualTo("/api/orgs");
        assertThat(answer.wwwAuthenticate()).hasValue("Bearer");
    }
}
