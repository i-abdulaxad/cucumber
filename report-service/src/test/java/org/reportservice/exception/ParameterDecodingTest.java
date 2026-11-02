package org.reportservice.exception;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.HttpCalls;
import org.reportservice.support.HttpCalls.Answer;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.RunData;
import org.reportservice.support.RunningReportService;
import org.reportservice.support.Uploads;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;

/**
 * T15 (RE12; AC-2.10, AC-6.5): a query parameter whose percent-encoding isn't valid UTF-8 is 400 MALFORMED_REQUEST on
 * every endpoint, not a 500; nothing is stored, and the log has no stack trace and no parameter value. Proven on a
 * running server on {@code 127.0.0.1}, because only Tomcat decodes parameters (MockMvc doesn't).
 */
@ExtendWith(OutputCaptureExtension.class)
class ParameterDecodingTest {

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

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @ParameterizedTest(name = "RE12 {0}: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"POST /api/ci/runs?name=%ED%A0%80", "POST /api/ci/runs?branch=%E0%A4",
            "GET {runs}?page=%FF", "GET {runs}?size=%ED%A0%80", "GET {runs}?status=%C3%28",
            "GET {runs}/{run id}/results?page=%FF"})
    @Tag("AC-2.10")
    @Tag("AC-6.5")
    void malformedPercentEncodingIsABadRequest(String request, CapturedOutput output) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        int runsBefore = Uploads.runs(jdbc);
        String token = Uploads.activeToken(jdbc, CHECKOUT);
        String[] parts = request.split(" ", 2);
        String path = parts[1].replace("{runs}", "/api/projects/" + CHECKOUT + "/runs")
                .replace("{run id}", run.id().toString());
        int logStart = output.getAll().length();

        Answer answer = parts[0].equals("POST")
                ? http.sendBytes("POST", path, CiSample.JSON.getBytes(StandardCharsets.UTF_8), "application/json",
                false, "Authorization", "Bearer " + token)
                : http.send("GET", path, null, "Authorization", DAN.bearer());

        answer.assertProblem(400, "MALFORMED_REQUEST");
        assertThat(Uploads.runs(jdbc)).isEqualTo(runsBefore);
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.hasStackTraceLine()).isFalse();
        assertThat(log.count("ERROR")).isZero();
        assertThat(log.lines()).noneMatch(line -> line.contains("�") || line.contains("%ED") || line.contains("%FF")
                || line.contains("%C3") || line.contains("%E0") || line.contains(token));
    }
}
