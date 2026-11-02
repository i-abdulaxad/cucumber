package org.reportservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.Plans;
import org.reportservice.support.Runs;
import org.reportservice.support.ScenarioServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.TestSettings;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * qa, leakage at root DEBUG in the shared context (AC-1.10, AC-7.2, AC-7.4; D17, RE8, RE9): unique markers are planted
 * in the token name, every query value, step texts, the error message and the request id; the success, 4xx, C7
 * failure and token paths are exercised; no captured line may contain a marker, the token, its prefix or hash, the
 * JWT, the service key or the C7 address. The root level is set right before use and restored in {@code finally}.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class QaLogLeakageTest {

    private static final String TOKENS = "/api/projects/" + CHECKOUT + "/upload-tokens";
    private static final String QUERY = "name=QaRunName91&environment=QaEnv92&build=QaBuild93&branch=QaBranch94"
            + "&commit=abcdef9495&ciUrl=https://qa-ci-host95.example/x";
    private static final List<String> MARKERS = List.of("QaTokName90", "QaRunName91", "QaEnv92", "QaBuild93",
            "QaBranch94", "abcdef9495", "qa-ci-host95", "QaStepText96", "QaErrMsg97", "QaBadCommit98",
            "Listing entities", "the customer pays by card", "expected rejected", "QaPlanName88", "QaComment99",
            "a basket with these items", "Total: 8.00");

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoggingSystem loggingSystem;

    @BeforeEach
    @AfterEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-7.4")
    @Tag("AC-1.10")
    @Tag("AC-7.2")
    @DisplayName("AC-7.4 qa: root DEBUG in the running context logs no secret, query value or content on any path")
    void rootDebugLeaksNothing(CapturedOutput output) throws Exception {
        String json = CiSample.JSON.replace("a basket", "QaStepText96 basket")
                .replace("expected rejected but was accepted", "QaErrMsg97 expected rejected");
        List<String> secrets = new ArrayList<>(List.of(CAI.token(), TestSettings.INTERNAL_SERVICE_KEY,
                "/internal/service", "127.0.0.1:" + TestSettings.AUTH_SERVICE_PORT));
        int start = output.getAll().length();
        String id;
        LogLevel before = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel();
        try {
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
            for (String pinned : List.of("org.hibernate.orm.core", "org.springframework.web.servlet.DispatcherServlet",
                    "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
                    "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
                    "org.springframework.security.web.FilterChainProxy", "jdk.internal.httpclient.debug")) {
                assertThat(LoggerFactory.getLogger(pinned).isDebugEnabled()).as(pinned).isFalse();
            }
            assertThat(LoggerFactory.getLogger("org.reportservice").isDebugEnabled()).isTrue();

            MvcResult created = mockMvc.perform(post(TOKENS).header(HttpHeaders.AUTHORIZATION, CAI.bearer())
                    .header("X-Request-Id", "qa-r-77").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"QaTokName90\"}")).andReturn();
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            String token = JsonPath.read(body(created), "$.token");
            id = JsonPath.read(body(created), "$.id");
            secrets.addAll(List.of(token, token.substring(0, 12), ReportData.sha256Hex(token)));

            assertThat(upload(token, QUERY, json).getResponse().getStatus()).isEqualTo(201);
            assertThat(upload(token, "commit=QaBadCommit98", json).getResponse().getStatus()).isEqualTo(400);
            assertThat(upload(token, "", json.replace("\"passed\"", "\"QaStepText96\"")).getResponse().getStatus())
                    .isEqualTo(422);
            stub.stop();
            assertThat(upload(token, QUERY, json).getResponse().getStatus()).isEqualTo(503);
            stub.reset();
            assertThat(mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, CAI.bearer())
                    .header("X-Request-Id", "qa-r-77")).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mockMvc.perform(post(TOKENS + "/" + id + "/revoke").header(HttpHeaders.AUTHORIZATION,
                    CAI.bearer()).header("X-Request-Id", "qa-r-77")).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(upload(token, QUERY, json).getResponse().getStatus()).isEqualTo(401);

            // Planning, a scenario-service failure, and recording with a comment (US-3, US-4)
            ScenarioServiceStub.get().reset();
            MvcResult plan = mockMvc.perform(post(Plans.runs(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, GUS.bearer())
                    .header("X-Request-Id", "qa-r-77").contentType(MediaType.APPLICATION_JSON)
                    .content(Plans.body("QaPlanName88", ",\"environment\":\"QaEnv92\"",
                            ScenarioServiceStub.PAYMENT))).andReturn();
            assertThat(plan.getResponse().getStatus()).isEqualTo(201);
            String runId = JsonPath.read(body(plan), "$.id");
            String resultId = JsonPath.read(body(Runs.read(mockMvc, DAN, CHECKOUT, "/" + runId + "/results")),
                    "$.items[0].id");
            assertThat(mockMvc.perform(put(Plans.runs(CHECKOUT) + "/" + runId + "/results/" + resultId)
                    .header(HttpHeaders.AUTHORIZATION, HAL.bearer()).header("X-Request-Id", "qa-r-77")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"FAILED\",\"comment\":\"QaComment99 secret\"}")).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(Runs.record(mockMvc, HAL, CHECKOUT, UUID.fromString(runId), UUID.fromString(resultId),
                    "{\"status\":\"BROKEN\",\"comment\":\"QaComment99 again\"}").getResponse().getStatus())
                    .isEqualTo(400);
            ScenarioServiceStub.get().stop();
            assertThat(Plans.plan(mockMvc, GUS, CHECKOUT, Plans.body("QaPlanName88", "", ScenarioServiceStub.PAYMENT))
                    .getResponse().getStatus()).isEqualTo(503);
            ScenarioServiceStub.get().reset();
            secrets.addAll(List.of(GUS.token(), HAL.token(), DAN.token(), "127.0.0.1:" + TestSettings.SCENARIO_SERVICE_PORT));
        } finally {
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, before);
        }

        String tokenId = id;
        RequestLog log = RequestLog.since(output, start);
        assertThat(log.lines()).as("lines at root DEBUG").hasSizeGreaterThan(20);
        // MockMvc's TestDispatcherServlet is a test-only subclass with its own logger name; production runs the
        // pinned DispatcherServlet (RE9), which RootDebugLoggingTest checks through real Tomcat.
        List<String> lines = log.lines().stream().filter(line -> !line.contains("o.s.t.web.servlet.TestDispatcherServlet")
                && !line.contains("org.springframework.test.web.servlet.TestDispatcherServlet")
                // the in-process auth-service stub (JDK HttpServer, thread "auth-service-stub") is test code too
                && !line.contains("service-stub]") && !line.contains("com.sun.net.httpserver")).toList();
        for (String line : lines) {
            for (String marker : MARKERS) {
                assertThat(line).as("marker " + marker).doesNotContain(marker);
            }
            for (String secret : secrets) {
                assertThat(line).as("a secret or the C7 address").doesNotContain(secret);
            }
            assertThat(line).doesNotContainIgnoringCase("authorization: bearer").doesNotContain("Bearer ey");
        }
        assertThat(log.aspectLines()).isNotEmpty().allSatisfy(aspect ->
                assertThat(aspect.line()).doesNotContain("requestId=-"));
        assertThat(log.aspectLines()).filteredOn(aspect -> aspect.line().contains("requestId=qa-r-77"))
                .extracting(RequestLog.AspectLine::message).anyMatch(m -> m.startsWith("Leaving UploadServiceImpl.upload"))
                .anyMatch(m -> m.contains("RunPlanningServiceImpl.plan")).anyMatch(m -> m.contains("RunRecordingServiceImpl.record"));
        assertThat(log.entries()).filteredOn(line -> line.contains("Upload stored")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("tokenId=" + tokenId).contains(" INFO "));
    }

    private MvcResult upload(String token, String query, String json) throws Exception {
        return mockMvc.perform(post(URI.create("/api/ci/runs" + (query.isEmpty() ? "" : "?" + query)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-Request-Id", "qa-r-77")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }
}
