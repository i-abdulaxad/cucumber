package org.reportservice.aop;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.HttpCalls;
import org.reportservice.support.HttpCalls.Answer;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RunningReportService;
import org.reportservice.support.TestSettings;
import org.reportservice.support.Uploads;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;

/**
 * AC-7.4 as extended by FU-2 (RE8, user 2026-10-29 and 2026-11-02), AC-1.10 at root DEBUG, and D17's upload line. A
 * running report-service on {@code 127.0.0.1} with the root logger at DEBUG: a user flow (create, list and revoke a
 * token) and a CI upload with a table, a doc string, a failure and query values. Every log line is free of tokens,
 * prefixes, hashes, the service key, JWTs, step texts, cells, doc strings, error messages, query values and entity
 * dumps, and the pinned loggers stay at INFO. Comments are recorded from T9 on; their part is added there.
 */
@ExtendWith(OutputCaptureExtension.class)
class RootDebugLoggingTest {

    /**
     * The loggers RE8 and FU-2 pin to INFO in {@code application.yaml}.
     */
    static final List<String> PINNED = List.of("org.hibernate.orm.core",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
            "org.springframework.security.web.FilterChainProxy",
            "jdk.internal.httpclient.debug");

    private static final java.util.regex.Pattern TEST_THREAD = java.util.regex.Pattern.compile("\\[\\s*Test worker\\]");
    private static final String STEP_TEXT = "the customer pays with QA-step-4417";
    private static final String CELL = "QA-cell-7731";
    private static final String DOC_STRING = "Total: QA-doc-9912";
    private static final String ERROR = "expected rejected but was accepted QA-err-1203";
    private static final String QUERY_NAME = "QA-query-5521";
    private static final String QUERY_ENVIRONMENT = "qa-env-3301";

    private static RunningReportService server;
    private static HttpCalls http;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws SQLException {
        server = RunningReportService.start();
        http = server.http();
        jdbc = server.bean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() throws SQLException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Tag("AC-7.4")
    @Tag("AC-1.10")
    @DisplayName("AC-7.4 At root DEBUG, a token flow and an upload log no secret, content or entity state")
    void rootDebugLogsNoSecretOrContent(CapturedOutput output) throws Exception {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        LogLevel rootFound = configuredLevel(logging, LoggingSystem.ROOT_LOGGER_NAME);
        int logStart = output.getAll().length();
        String token;
        String runId;
        try {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);

            Answer created = http.send("POST", "/api/projects/" + CHECKOUT + "/upload-tokens",
                    "{\"name\":\"GitHub Actions\"}", "Authorization", CAI.bearer(), "X-Request-Id", "r-74");
            assertThat(created.status()).as(created.body()).isEqualTo(201);
            token = JsonPath.read(created.body(), "$.token");
            String tokenId = JsonPath.read(created.body(), "$.id");
            assertThat(http.send("GET", "/api/projects/" + CHECKOUT + "/upload-tokens", null,
                    "Authorization", CAI.bearer()).status()).isEqualTo(200);

            Answer upload = http.sendBytes("POST", Uploads.PATH + "?name=" + QUERY_NAME + "&environment="
                            + QUERY_ENVIRONMENT + "&commit=abcd1234&build=1.4.2&branch=main",
                    sample().getBytes(StandardCharsets.UTF_8),
                    "application/json", false, "Authorization", "Bearer " + token, "X-Request-Id", "r-74");
            assertThat(upload.status()).as(upload.body()).isEqualTo(201);
            runId = JsonPath.read(upload.body(), "$.id");

            assertThat(http.send("POST", "/api/projects/" + CHECKOUT + "/upload-tokens/" + tokenId + "/revoke", null,
                    "Authorization", CAI.bearer()).status()).isEqualTo(200);
            for (String logger : PINNED) {
                assertThat(logging.getLoggerConfiguration(logger).getEffectiveLevel()).as(logger)
                        .isEqualTo(LogLevel.INFO);
            }
        } finally {
            // Log levels are JVM-global: put back what the test found
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootFound);
        }

        // The test's own HTTP client (the JDK's, on the test thread) logs the URLs it sends at DEBUG; those lines are
        // the test's, not report-service's. report-service's clients log on their own HttpClient threads.
        String log = output.getAll().substring(logStart).lines()
                .filter(line -> !(line.contains("jdk.internal.httpclient.debug") && TEST_THREAD.matcher(line).find()))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(log).contains(" DEBUG ");
        assertThat(log).contains("Upload stored: run " + runId + " with 1 test cases in ");
        assertThat(log.lines().filter(line -> line.contains("Upload stored: run "))).singleElement()
                .satisfies(line -> assertThat(line).contains("requestId=r-74").contains(" INFO "));
        assertThat(log).doesNotContain(token, token.substring(0, 12), ReportData.sha256Hex(token),
                TestSettings.INTERNAL_SERVICE_KEY, CAI.token(), STEP_TEXT, CELL, DOC_STRING, "QA-err-1203",
                "expected rejected", QUERY_NAME, QUERY_ENVIRONMENT, "branch=main", "GitHub Actions",
                "Listing entities");
        // RE9: report-service's own JDK HTTP clients (C5, C7) log their URLs at DEBUG unless pinned
        assertThat(log.lines().filter(line -> line.contains("jdk.internal.httpclient.debug"))).isEmpty();
    }

    /**
     * One feature, one scenario: a hidden Before hook, a step with a table, one with a doc string, a failure.
     */
    private static String sample() {
        return """
                [{"uri": "features/checkout/payment.feature", "name": "Payment", "elements": [
                  {"type": "scenario", "name": "Pay by card", "line": 8, "steps": [
                    {"hidden": true, "keyword": "Before", "result": {"status": "passed", "duration": 400000}},
                    {"keyword": "Given ", "name": "a basket with these items:", "result": {"status": "passed"},
                     "rows": [{"cells": ["item", "price"]}, {"cells": ["%s", "8.00"]}]},
                    {"keyword": "When ", "name": "%s", "result": {"status": "failed", "error_message": "%s"}},
                    {"keyword": "Then ", "name": "the receipt is:", "result": {"status": "skipped"},
                     "doc_string": {"value": "%s"}}]}]}]
                """.formatted(CELL, STEP_TEXT, ERROR, DOC_STRING);
    }

    private static LogLevel configuredLevel(LoggingSystem logging, String logger) {
        LoggerConfiguration configuration = logging.getLoggerConfiguration(logger);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }
}
