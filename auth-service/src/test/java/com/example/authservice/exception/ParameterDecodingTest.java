package com.example.authservice.exception;

import com.example.authservice.aop.RequestLog;
import com.example.authservice.support.HttpCalls;
import com.example.authservice.support.HttpCalls.Answer;
import com.example.authservice.support.RunningAuthService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening T9 (FU-3, E17's condition, as report-service RE12): a query parameter whose percent-encoding
 * isn't valid UTF-8 (Tomcat's {@code InvalidParameterException}) never leads to a 500. auth-service declares no
 * {@code @RequestParam}, so an endpoint keeps its normal answer, or answers 400 MALFORMED_REQUEST where a parameter is
 * decoded; the log has no ERROR line, no stack trace and nothing of the value (the marker {@code QaLeakFu3}). Proven on
 * a running server on {@code 127.0.0.1}, because only Tomcat decodes parameters (MockMvc doesn't).
 */
@ExtendWith(OutputCaptureExtension.class)
class ParameterDecodingTest {

    private static final String MARKER = "QaLeakFu3";
    private static final String PASSWORD = "Secret123!";
    private static final String ANA = "ana.t9@example.com";

    private static RunningAuthService server;
    private static HttpCalls http;
    private static String anaToken;

    @BeforeAll
    static void start() throws Exception {
        server = RunningAuthService.start();
        http = server.http();
        assertThat(http.send("POST", "/api/auth/register", registration(ANA)).status()).isEqualTo(201);
        Answer login = http.send("POST", "/api/auth/login", login());
        assertThat(login.status()).isEqualTo(200);
        anaToken = JsonPath.read(login.body(), "$.accessToken");
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Tag("T9")
    void loginWithValidCredentialsIsNever500(CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("POST", "/api/auth/login?x=QaLeakFu3%FF", login());

        assertNormalOrMalformed(answer, 200);
        assertCleanLog(output, logStart);
    }

    @Test
    @Tag("T9")
    void registrationWithAValidBodyIsNever500(CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("POST", "/api/auth/register?x=QaLeakFu3%E0%A4", registration("ben.t9@example.com"));

        assertNormalOrMalformed(answer, 201);
        assertCleanLog(output, logStart);
    }

    @Test
    @Tag("T9")
    void listingOrganizationsWithAValidTokenIsNever500(CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("GET", "/api/orgs?x=QaLeakFu3%FF", null, "Authorization", "Bearer " + anaToken);

        assertNormalOrMalformed(answer, 200);
        assertCleanLog(output, logStart);
    }

    @Test
    @Tag("T9")
    void withoutATokenTheAnswerIsStill401(CapturedOutput output) throws Exception {
        int logStart = output.getAll().length();

        Answer answer = http.send("GET", "/api/orgs?x=QaLeakFu3%FF", null);

        answer.assertProblem(401, "UNAUTHENTICATED");
        assertCleanLog(output, logStart);
    }

    /**
     * The endpoint's normal answer, or 400 MALFORMED_REQUEST if it decodes the parameter; never a 500.
     */
    private static void assertNormalOrMalformed(Answer answer, int normalStatus) {
        assertThat(answer.status()).as("status").isIn(normalStatus, 400);
        if (answer.status() == 400) {
            answer.assertProblem(400, "MALFORMED_REQUEST");
        }
    }

    private static void assertCleanLog(CapturedOutput output, int logStart) {
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("ERROR")).as("ERROR lines").isZero();
        assertThat(log.hasStackTraceLine()).as("a stack trace line").isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(MARKER));
    }

    private static String registration(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Ana Tee\"}";
    }

    private static String login() {
        return "{\"email\":\"" + ANA + "\",\"password\":\"" + PASSWORD + "\"}";
    }
}
