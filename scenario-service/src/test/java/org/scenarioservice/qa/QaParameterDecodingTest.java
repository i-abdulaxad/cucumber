package org.scenarioservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.scenarioservice.ScenarioServiceApplication;
import org.scenarioservice.aop.RequestLog;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.HttpCalls;
import org.scenarioservice.support.HttpCalls.Answer;
import org.scenarioservice.support.ScratchDatabase;
import org.scenarioservice.support.TestSettings;
import org.scenarioservice.support.TestTokens;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.IVY;

/**
 * qa, platform-hardening FU-3 / T9 (E18): malformed query percent-encoding on a running scenario-service (only
 * Tomcat decodes parameters). {@code listFeatures} is the only endpoint that reads query parameters: it answers 400
 * MALFORMED_REQUEST, after the 401 and before the access check (A3), so a non-member or an unknown project also gets
 * 400 and C5 is never asked. Every other endpoint keeps its normal answer. No captured line contains the marker, an
 * ERROR level or a stack trace. Broken escapes go over a raw socket, because the JDK client refuses to send them.
 */
@ExtendWith(OutputCaptureExtension.class)
class QaParameterDecodingTest {

    private static final String MARKER = "QaLeakFu3";

    private static ScratchDatabase database;
    private static ConfigurableApplicationContext app;
    private static int port;
    private static HttpCalls http;
    private static String feature;

    private final AccessCheckStub stub = AccessCheckStub.get();

    @BeforeAll
    static void start() throws Exception {
        database = ScratchDatabase.create();
        app = new SpringApplicationBuilder(ScenarioServiceApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=",
                "--JWT_SECRET=" + TestSettings.JWT_SECRET,
                "--app.auth-service.base-url=" + TestSettings.accessCheckBaseUrl(),
                "--SCENARIO_DB_URL=" + database.url(), "--SCENARIO_DB_USERNAME=" + database.username(),
                "--SCENARIO_DB_PASSWORD=" + database.password(),
                "--spring.datasource.hikari.data-source-properties.options=-c lock_timeout=1s");
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        http = new HttpCalls(port);
        AccessCheckStub.get().reset();
        Answer created = http.send("POST", "/api/projects/" + CHECKOUT + "/features",
                "{\"path\":\"features/qa9/payment.feature\",\"source\":" + json(GherkinSources.PAYMENT) + "}",
                "Authorization", GUS.bearer());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        feature = JsonPath.read(created.body(), "$.id");
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (app != null) {
                app.close();
            }
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "FU-3 qa: GET {0}{1} as {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "features                  | ?tag=QaLeakFu3%FF                | gus     | 400 | 0",
            "features                  | ?archived=QaLeakFu3%ED%A0%80     | gus     | 400 | 0",
            "features                  | ?QaLeakFu3%FF=1                  | gus     | 400 | 0",
            "features                  | ?tag=QaLeakFu3%C0%AF             | gus     | 400 | 0",
            "features                  | ?tag=@ok&archived=QaLeakFu3%E0%A4 | gus    | 400 | 0",
            "features                  | ?tag=QaLeakFu3%                  | gus     | 400 | 0",
            "features                  | ?tag=QaLeakFu3%G1                | gus     | 400 | 0",
            "features                  | ?tag=QaLeakFu3%F                 | gus     | 400 | 0",
            "features                  | ?tag=QaLeakFu3%FF                | none    | 401 | 0",
            "features                  | ?tag=QaLeakFu3%FF                | expired | 401 | 0",
            "features                  | ?tag=QaLeakFu3%FF                | ivy     | 400 | 0",
            "unknown-project/features  | ?tag=QaLeakFu3%FF                | gus     | 400 | 0",
            "features/{f}              | ?x=QaLeakFu3%FF                  | gus     | 200 | 1",
            "features/{f}              | ?x=QaLeakFu3%G1                  | gus     | 200 | 1",
            "features/{f}              | ?x=QaLeakFu3%FF                  | ivy     | 403 | 1",
            "features/{f}/versions     | ?x=QaLeakFu3%FF                  | gus     | 200 | 1",
            "features/{f}/versions/1   | ?x=QaLeakFu3%FF                  | gus     | 200 | 1",
            "features/{f}/test-cases   | ?x=QaLeakFu3%FF                  | gus     | 200 | 1",
            "tags                      | ?x=QaLeakFu3%FF                  | gus     | 200 | 1",
            "features/{random}         | ?x=QaLeakFu3%FF                  | gus     | 404 | 1"})
    @Tag("T9")
    void malformedEncoding(String relative, String query, String caller, int status, int accessChecks,
                           CapturedOutput output) throws Exception {
        String path = (relative.startsWith("unknown-project") ? "/api/projects/" + UUID.randomUUID()
                + relative.substring("unknown-project".length()) : "/api/projects/" + CHECKOUT + "/" + relative)
                .replace("{f}", feature).replace("{random}", UUID.randomUUID().toString()) + query;
        String authorization = switch (caller) {
            case "gus" -> GUS.bearer();
            case "ivy" -> IVY.bearer();
            case "expired" -> "Bearer " + TestTokens.expired(GUS.id(), "gus@example.com");
            default -> null;
        };
        int logStart = output.getAll().length();

        String[] answer = raw(path, authorization);

        assertThat(Integer.parseInt(answer[0])).as(path + " " + answer[1]).isEqualTo(status);
        if (status == 400) {
            assertThat(answer[1]).contains("\"code\":\"MALFORMED_REQUEST\"")
                    .contains("\"detail\":\"The request could not be read.\"");
        }
        assertThat(answer[1]).doesNotContain(MARKER + "%").doesNotContain("Character decoding")
                .doesNotContain("Exception");
        assertThat(stub.received()).as("access checks").hasSize(accessChecks);
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("ERROR")).as("ERROR lines").isZero();
        assertThat(log.hasStackTraceLine()).as("a stack trace line").isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(MARKER));
    }

    /** GET with the request line exactly as written; returns {status, body}. */
    private static String[] raw(String target, String authorization) throws Exception {
        StringBuilder request = new StringBuilder("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nConnection: close\r\n");
        if (authorization != null) {
            request.append("Authorization: ").append(authorization).append("\r\n");
        }
        request.append("\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int bodyStart = response.indexOf("\r\n\r\n");
            return new String[]{response.substring(9, 12), bodyStart < 0 ? "" : response.substring(bodyStart + 4)};
        }
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
