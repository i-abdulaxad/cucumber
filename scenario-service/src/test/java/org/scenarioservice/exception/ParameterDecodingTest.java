package org.scenarioservice.exception;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.aop.RequestLog;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.HttpCalls;
import org.scenarioservice.support.HttpCalls.Answer;
import org.scenarioservice.support.RunningScenarioService;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * platform-hardening T9 (FU-3, E18, as report-service RE12): a query parameter whose percent-encoding isn't valid UTF-8
 * (Tomcat's {@code InvalidParameterException}, "Character decoding failed") is 400 MALFORMED_REQUEST, not a 500, and
 * the log has no ERROR line, no stack trace and nothing of the value (the marker {@code QaLeakFu3}). Proven on a
 * running server on {@code 127.0.0.1}, because only Tomcat decodes parameters (MockMvc doesn't).
 */
@ExtendWith(OutputCaptureExtension.class)
class ParameterDecodingTest {

    private static final String MARKER = "QaLeakFu3";
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

    @ParameterizedTest(name = "T9 GET features{0} with a valid JWT: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"?tag=QaLeakFu3%FF", "?archived=%ED%A0%80", "?x=QaLeakFu3%E0%A4"})
    @Tag("T9")
    void aMalformedQueryParameterIsABadRequest(String query, CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("GET", FEATURES + query, null, "Authorization", GUS.bearer());

        answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(stub.received()).as("access checks").isEmpty();
        assertCleanLog(output, logStart);
    }

    @Test
    @Tag("T9")
    void withoutATokenTheAnswerIsStill401(CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("GET", FEATURES + "?tag=QaLeakFu3%FF", null);

        answer.assertProblem(401, "UNAUTHENTICATED");
        assertCleanLog(output, logStart);
    }

    @Test
    @Tag("T9")
    void aFeatureEndpointAnswersNormallyOrWith400AndNever500(CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("GET", FEATURES + "/" + UUID.randomUUID() + "?x=QaLeakFu3%FF", null,
                "Authorization", GUS.bearer());

        assertThat(answer.status()).as("status").isIn(404, 400);
        answer.assertProblem(answer.status(), answer.status() == 404 ? "FEATURE_NOT_FOUND" : "MALFORMED_REQUEST");
        assertCleanLog(output, logStart);
    }

    private static void assertCleanLog(CapturedOutput output, int logStart) {
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("ERROR")).as("ERROR lines").isZero();
        assertThat(log.hasStackTraceLine()).as("a stack trace line").isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(MARKER));
    }
}
