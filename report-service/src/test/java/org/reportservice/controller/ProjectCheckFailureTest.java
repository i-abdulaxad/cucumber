package org.reportservice.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.TestSettings;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CHECKOUT;

/**
 * AC-2.7 (T7's test first, D5): the project check (C7) fails closed. Whatever goes wrong, the upload is 503
 * PROJECT_CHECK_UNAVAILABLE, nothing is stored ({@code lastUsedAt} unchanged), and one WARN line names the failure type
 * without URL, port, key or token.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ProjectCheckFailureTest {

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @ParameterizedTest(name = "AC-2.7 the C7 stub {0}: 503 PROJECT_CHECK_UNAVAILABLE, nothing stored")
    @ValueSource(strings = {"is not listening", "answers 500", "answers after 3 s",
            "answers 200 with an unreadable body", "answers 200 with another projectId", "answers 401 (wrong key)",
            "answers 404", "answers 200 without archived"})
    @Tag("AC-2.7")
    void theProjectCheckFailsClosed(String behaviour, CapturedOutput output) throws Exception {
        String t1 = Uploads.activeToken(jdbc, CHECKOUT);
        switch (behaviour) {
            case "is not listening" -> stub.stop();
            case "answers 500" -> stub.answer(500, "{}");
            case "answers after 3 s" -> stub.delay(Duration.ofSeconds(3));
            case "answers 200 with an unreadable body" -> stub.answer(200, "<html>ok</html>");
            case "answers 200 with another projectId" -> stub.answer(200, AuthServiceStub.statusBody(BILLING));
            case "answers 401 (wrong key)" -> stub.answer(401, "{\"code\":\"UNAUTHENTICATED\"}");
            case "answers 404" -> stub.answer(404, "{\"code\":\"NOT_FOUND\"}");
            default -> stub.answer(200, AuthServiceStub.statusBody(CHECKOUT).replace(",\"archived\":false", ""));
        }
        int logStart = output.getAll().length();
        long start = System.nanoTime();

        MvcResult result = Uploads.upload(mockMvc, t1, "", CiSample.JSON);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertProblem(result, 503, "PROJECT_CHECK_UNAVAILABLE");
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens WHERE last_used_at IS NOT NULL",
                Integer.class)).isZero();
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.entries()).filteredOn(line -> line.contains("Project check unavailable")).singleElement()
                .satisfies(line -> assertThat(line).contains(" WARN "));
        // Besides it, only the aspect's standard outcome line for a slow call (platform-hardening AC-3.2)
        assertThat(log.entries()).filteredOn(line -> line.contains(" WARN ")
                && !line.contains("Project check unavailable")).allSatisfy(line -> assertThat(line)
                .contains("Slow call UploadServiceImpl.upload: business error PROJECT_CHECK_UNAVAILABLE"));
        assertThat(log.hasStackTraceLine()).isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(t1) || line.contains(TestSettings.INTERNAL_SERVICE_KEY)
                || line.contains(String.valueOf(TestSettings.AUTH_SERVICE_PORT)) || line.contains("/internal/"));
    }
}
