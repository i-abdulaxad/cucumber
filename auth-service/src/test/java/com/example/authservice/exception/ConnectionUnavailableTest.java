package com.example.authservice.exception;

import com.example.authservice.aop.RequestLog;
import com.example.authservice.support.HttpCalls;
import com.example.authservice.support.HttpCalls.Answer;
import com.example.authservice.support.RunningAuthService;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening US-3 (FU-19, D7): when no database connection is available, every request is a 503
 * SERVICE_UNAVAILABLE Problem, never 401 or Boot's body, logged once at WARN; the service recovers by itself.
 * Background: a running auth-service (D9, {@link RunningAuthService}) with a pool of 2 connections and a
 * connection-timeout of 500 ms. It's started once for this class and closed afterwards.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConnectionUnavailableTest {

    private static final int POOL_SIZE = 2;
    private static final String REQUEST_ID = "ph-db-1";
    private static final String PASSWORD = "Secret123!";
    private static final String ANA = "ana@example.com";
    private static final Duration ANSWERED_WITHIN = Duration.ofSeconds(2);

    private static RunningAuthService server;
    private static HttpCalls http;
    private static String anaToken;

    @BeforeAll
    static void startWithASmallPool() throws Exception {
        server = RunningAuthService.start("--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                "--spring.datasource.hikari.connection-timeout=500");
        http = server.http();
        assertThat(http.send("POST", "/api/auth/register", registration(ANA, "Ana Lima")).status()).isEqualTo(201);
        Answer login = http.send("POST", "/api/auth/login", login(PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        anaToken = JsonPath.read(login.body(), "$.accessToken");
    }

    @AfterAll
    static void stop() throws SQLException {
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest(name = "AC-3.1 {0}: 503 SERVICE_UNAVAILABLE within 2 s")
    @ValueSource(strings = {"GET /api/orgs with a valid token", "login with the correct password",
            "login with a wrong password", "register zed", "GET /internal/projects/{P}/access with a valid token"})
    @Tag("AC-3.1")
    void connectionUnavailableIsA503Problem(String request) throws Exception {
        String path = path(request);
        List<Connection> held = holdEveryConnection();
        Answer answer;
        Duration took;
        try {
            long start = System.nanoTime();
            answer = send(request, path);
            took = Duration.ofNanos(System.nanoTime() - start);
        } finally {
            release(held);
        }

        DocumentContext problem = answer.assertProblem(503, "SERVICE_UNAVAILABLE");
        assertThat(took).isLessThan(ANSWERED_WITHIN);
        assertThat(problem.read("$.detail", String.class))
                .isEqualTo("The service is temporarily unavailable. Try again later.");
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        assertThat(problem.read("$.instance", String.class)).isEqualTo(path);
        assertThat(answer.retryAfter()).as("no Retry-After").isEmpty();
        assertThat(answer.wwwAuthenticate()).as("never a login challenge").isEmpty();
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 it is logged once at WARN with the failure class, without stack trace, token or email")
    void loggedOnceWithoutDetail(CapturedOutput output) throws Exception {
        List<Connection> held = holdEveryConnection();
        int logStart = output.getAll().length();
        try {
            assertThat(send("GET /api/orgs with a valid token", "/api/orgs").status()).isEqualTo(503);
        } finally {
            release(held);
        }

        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("WARN")).as("WARN entries: %s", log.entries()).isEqualTo(1);
        assertThat(log.entries()).filteredOn(line -> line.contains(" WARN "))
                .singleElement().asString().contains("SQLTransientConnectionException");
        assertThat(log.count("ERROR")).as("ERROR entries: %s", log.entries()).isZero();
        assertThat(log.hasStackTraceLine()).as("no stack trace").isFalse();
        assertThat(String.join("\n", log.lines())).doesNotContain(anaToken, ANA, PASSWORD);
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("AC-3.3 once the connections are released, a wrong password is 401 INVALID_CREDENTIALS again")
    void theServiceRecoversByItself() throws Exception {
        release(holdEveryConnection());

        Answer answer = http.send("POST", "/api/auth/login", login("Wrong-password-1"), "X-Request-Id", REQUEST_ID);

        answer.assertProblem(401, "INVALID_CREDENTIALS");
    }

    private static Answer send(String request, String path) throws Exception {
        String auth = "Bearer " + anaToken;
        return switch (request) {
            case "login with the correct password" ->
                    http.send("POST", path, login(PASSWORD), "X-Request-Id", REQUEST_ID);
            case "login with a wrong password" ->
                    http.send("POST", path, login("Wrong-password-1"), "X-Request-Id", REQUEST_ID);
            case "register zed" ->
                    http.send("POST", path, registration("zed@example.com", "Zed Roe"), "X-Request-Id", REQUEST_ID);
            default -> http.send("GET", path, null, "X-Request-Id", REQUEST_ID, "Authorization", auth);
        };
    }

    private static String path(String request) {
        if (request.startsWith("login")) {
            return "/api/auth/login";
        }
        if (request.startsWith("register")) {
            return "/api/auth/register";
        }
        return request.startsWith("GET /internal") ? "/internal/projects/" + UUID.randomUUID() + "/access"
                : "/api/orgs";
    }

    /** Takes every connection of the pool, so that the next request waits its connection-timeout and gets none. */
    private static List<Connection> holdEveryConnection() throws SQLException {
        DataSource dataSource = server.bean(DataSource.class);
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            held.add(dataSource.getConnection());
        }
        return held;
    }

    private static void release(List<Connection> held) throws SQLException {
        for (Connection connection : held) {
            connection.close();
        }
    }

    private static String registration(String email, String fullName) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"" + fullName + "\"}";
    }

    private static String login(String password) {
        return "{\"email\":\"" + ANA + "\",\"password\":\"" + password + "\"}";
    }
}
