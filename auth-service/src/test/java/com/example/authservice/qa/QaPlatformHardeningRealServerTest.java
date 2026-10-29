package com.example.authservice.qa;

import com.example.authservice.support.HttpCalls;
import com.example.authservice.support.HttpCalls.Answer;
import com.example.authservice.support.RunningAuthService;
import com.example.authservice.support.TestDatabase;
import com.example.authservice.support.TestJwt;
import com.example.authservice.repository.UserRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * QA adversarial checks of platform-hardening US-2 (D3, D4, D5) and US-3 (D7) on a running auth-service (D9): more
 * firewall forms than the AC rows, content negotiation, TRACE with a cookie, direct {@code /error} variants, a burst of
 * concurrent requests while the pool is exhausted, and a database that refuses new connections. The server (pool of 3,
 * connection-timeout 500 ms) is started once and closed afterwards ({@link RunningAuthService}), so no cached test
 * context is added.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("QA platform-hardening US-2 and US-3: auth-service on a real server")
class QaPlatformHardeningRealServerTest {

    private static final int POOL_SIZE = 3;
    private static final String PASSWORD = "Secret123!";
    private static final String ANA = "ana@example.com";
    private static final String UNAVAILABLE_DETAIL = "The service is temporarily unavailable. Try again later.";

    private static RunningAuthService server;
    private static HttpCalls http;
    private static String anaToken;

    @BeforeAll
    static void start() throws Exception {
        server = RunningAuthService.start("--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                "--spring.datasource.hikari.connection-timeout=500");
        http = server.http();
        assertThat(http.send("POST", "/api/auth/register", registration(ANA)).status()).isEqualTo(201);
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

    // ---- US-2: rejections before Spring MVC --------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST, /api/auth/login;jsessionid=abc,   no token",
            "POST, /api/auth/register/%2e%2e/register, no token",
            "GET,  /api/orgs/%252e%252e/x,         valid token",
            "GET,  /api/orgs/%3Bx,                 valid token",
            "GET,  /api/orgs/..;/x,                valid token",
            "GET,  /api/orgs//x,                   expired token"})
    @Tag("AC-2.1")
    @DisplayName("AC-2.1 (more rows): firewall rejections are 400 MALFORMED_REQUEST Problems, nothing created")
    void moreFirewallFormsAreProblems(String method, String path, String auth) throws Exception {
        long users = server.bean(UserRepository.class).count();
        String body = method.equals("POST") ? registration("qa-ph-fw@example.com") : null;
        Answer answer = switch (auth) {
            case "valid token" -> http.send(method, path, body, "Authorization", "Bearer " + anaToken,
                    "X-Request-Id", "qa-ph-auth-2");
            case "expired token" -> http.send(method, path, body, "Authorization",
                    "Bearer " + TestJwt.expired(UUID.randomUUID(), ANA), "X-Request-Id", "qa-ph-auth-2");
            default -> http.send(method, path, body, "X-Request-Id", "qa-ph-auth-2");
        };

        DocumentContext problem = answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(problem.read("$.instance", String.class)).isEqualTo(path);
        assertThat(problem.read("$.requestId", String.class)).isEqualTo("qa-ph-auth-2");
        assertThat(answer.body()).doesNotContain(anaToken);
        assertThat(server.bean(UserRepository.class).count()).as("no user created").isEqualTo(users);
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/html", "application/xml", "text/plain"})
    @Tag("AC-2.1")
    @DisplayName("D3: a firewall rejection is the 400 Problem whatever the client accepts")
    void theProblemIgnoresTheAcceptHeader(String accept) throws Exception {
        Answer answer = http.send("POST", "/api/auth/register;x=1", registration("qa-ph-acc@example.com"),
                "Accept", accept);

        answer.assertProblem(400, "MALFORMED_REQUEST");
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("AC-2.2 (more): TRACE with a valid token, a cookie and a custom header echoes none of them")
    void traceEchoesNothing() throws Exception {
        Answer answer = http.send("TRACE", "/api/auth/login", null, "Authorization", "Bearer " + anaToken,
                "Cookie", "SESSION=qa-cookie-marker", "X-Qa-Marker", "qa-header-marker");

        DocumentContext problem = answer.assertProblem(405, "METHOD_NOT_ALLOWED");
        assertThat(problem.read("$.instance", String.class)).isEqualTo("/api/auth/login");
        assertThat(answer.body()).doesNotContain(anaToken, "qa-cookie-marker", "qa-header-marker", "Cookie");
    }

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST,   /error,            valid token,   404",
            "DELETE, /error,            valid token,   404",
            "GET,    /error?status=500, valid token,   404",
            "GET,    /error,            expired token, 401",
            "POST,   /error,            no token,      401",
            "GET,    /error;x=1,        valid token,   400"})
    @Tag("AC-2.3")
    @DisplayName("AC-2.3 (more rows): a direct /error stays an unrouted path whatever the method, query or token")
    void directErrorVariants(String method, String target, String auth, int status) throws Exception {
        Answer answer = switch (auth) {
            case "valid token" -> http.send(method, target, null, "Authorization", "Bearer " + anaToken);
            case "expired token" -> http.send(method, target, null, "Authorization",
                    "Bearer " + TestJwt.expired(UUID.randomUUID(), ANA));
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
    }

    // ---- US-3: connection unavailable --------------------------------------------------------------------------------

    @Test
    @Tag("AC-3.1")
    @Tag("AC-3.3")
    @DisplayName("AC-3.1 under load: 12 concurrent requests while the pool is exhausted are all 503 in time; then recovery, no leak")
    void aBurstWhileThePoolIsExhaustedIsAll503(CapturedOutput output) throws Exception {
        List<Connection> held = holdEveryConnection();
        List<Answer> answers = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        long start = System.nanoTime();
        try {
            List<Callable<Answer>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(() -> http.send("GET", "/api/orgs", null, "Authorization", "Bearer " + anaToken));
                calls.add(() -> http.send("POST", "/api/auth/login", login(PASSWORD)));
                calls.add(() -> http.send("POST", "/api/auth/login", login("Wrong-password-1")));
                calls.add(() -> http.send("POST", "/api/auth/register", registration("qa-ph-burst@example.com")));
            }
            for (Future<Answer> answer : pool.invokeAll(calls)) {
                answers.add(answer.get());
            }
        } finally {
            pool.shutdownNow();
            release(held);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(answers).hasSize(12).allSatisfy(answer -> {
            DocumentContext problem = answer.assertProblem(503, "SERVICE_UNAVAILABLE");
            assertThat(problem.read("$.detail", String.class)).isEqualTo(UNAVAILABLE_DETAIL);
            assertThat(answer.wwwAuthenticate()).isEmpty();
            assertThat(answer.retryAfter()).isEmpty();
        });
        assertThat(took).as("12 concurrent requests, each waiting at most 500 ms").isLessThan(Duration.ofSeconds(3));
        assertThat(output.getAll()).doesNotContain(anaToken, PASSWORD, "Wrong-password-1", "qa-ph-burst@example.com",
                "\tat ", "Caused by:");

        // And the service recovers by itself, with no connection left behind
        assertThat(http.send("POST", "/api/auth/login", login(PASSWORD)).status()).isEqualTo(200);
        assertThat(server.bean(UserRepository.class).count()).as("no registration got through").isEqualTo(1);
        HikariDataSource hikari = server.bean(DataSource.class).unwrap(HikariDataSource.class);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).as("active connections").isZero());
    }

    @Test
    @Tag("AC-3.1")
    @Tag("AC-3.3")
    @DisplayName("D7 'the database refuses the connection': with new connections refused, requests are 503; then recovery")
    void aDatabaseThatRefusesConnectionsIs503() throws Exception {
        String database = databaseName();
        try {
            // Given the database accepts no new connection and the pool's connections are gone
            admin("ALTER DATABASE " + database + " ALLOW_CONNECTIONS false");
            admin("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + database + "'");
            Thread.sleep(1_500); // past HikariCP's 500 ms alive-bypass window, so dead connections are validated away

            // When
            long start = System.nanoTime();
            Answer orgs = http.send("GET", "/api/orgs", null, "Authorization", "Bearer " + anaToken,
                    "X-Request-Id", "qa-ph-refused");
            Duration orgsTook = Duration.ofNanos(System.nanoTime() - start);
            start = System.nanoTime();
            Answer wrongPassword = http.send("POST", "/api/auth/login", login("Wrong-password-1"));
            Duration loginTook = Duration.ofNanos(System.nanoTime() - start);

            // Then each is the 503 Problem within AC-3.1's 2 s
            orgs.assertProblem(503, "SERVICE_UNAVAILABLE");
            assertThat(JsonPath.<String>read(orgs.body(), "$.requestId")).isEqualTo("qa-ph-refused");
            wrongPassword.assertProblem(503, "SERVICE_UNAVAILABLE");
            assertThat(orgsTook).as("GET /api/orgs").isLessThan(Duration.ofSeconds(2));
            assertThat(loginTook).as("login").isLessThan(Duration.ofSeconds(2));
        } finally {
            admin("ALTER DATABASE " + database + " ALLOW_CONNECTIONS true");
        }

        // And once connections are accepted again, the service recovers by itself
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                http.send("POST", "/api/auth/login", login("Wrong-password-1")).assertProblem(401, "INVALID_CREDENTIALS"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

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

    private static String databaseName() {
        String url = server.bean(Environment.class).getProperty("AUTH_DB_URL");
        assertThat(url).isNotNull().contains("/scratch_");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static void admin(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestDatabase.POSTGRES.getJdbcUrl(),
                TestDatabase.POSTGRES.getUsername(), TestDatabase.POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String registration(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Qa Person\"}";
    }

    private static String login(String password) {
        return "{\"email\":\"" + ANA + "\",\"password\":\"" + password + "\"}";
    }
}
