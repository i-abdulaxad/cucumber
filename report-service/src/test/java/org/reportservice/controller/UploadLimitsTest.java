package org.reportservice.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.HttpCalls;
import org.reportservice.support.HttpCalls.Answer;
import org.reportservice.support.ReportData;
import org.reportservice.support.RunningReportService;
import org.reportservice.support.Uploads;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ReportsFixture.CHECKOUT;

/**
 * AC-2.9 (D9) and T7's stop condition, on a running server on {@code 127.0.0.1} (MockMvc always sets
 * {@code Content-Length}): bodies over 20 MiB are 413 with or without {@code Content-Length}, a non-JSON content
 * type is 415, more than 5,000 test cases is 422, and an upload just under the limit stays under 5 s. Started once
 * for the class, so no cached test context is added.
 */
class UploadLimitsTest {

    private static final int LIMIT = 20 * 1024 * 1024;

    private static RunningReportService server;
    private static HttpCalls http;
    private static JdbcTemplate jdbc;

    private String t1;

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
        ReportData.reset(jdbc);
        t1 = Uploads.activeToken(jdbc, CHECKOUT);
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("AC-2.9 The same body chunked, without Content-Length: 413 once reading reaches the limit")
    void aChunkedBodyOverTheLimitIsTooLarge() throws Exception {
        byte[] body = padded(CiSample.JSON, LIMIT + 1);

        Answer answer = http.sendBytes("POST", Uploads.PATH, body, "application/json", true,
                "Authorization", "Bearer " + t1);

        answer.assertProblem(413, "UPLOAD_TOO_LARGE");
        assertThat(Uploads.runs(jdbc)).isZero();
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("AC-2.9, BD-15 Content-Length 20,971,521: 413 before any body byte is sent or read")
    void aDeclaredLengthOverTheLimitIsAnsweredBeforeTheBody() throws Exception {
        // Only the request head is sent: the answer can't depend on the body. (With a full 20 MiB body, the server's
        // close after its early answer can reset the connection before a client has read that answer.)
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST " + Uploads.PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + t1 + "\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + (LIMIT + 1) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            StringBuilder answer = new StringBuilder();
            byte[] buffer = new byte[4096];
            for (int read; !answer.toString().contains("UPLOAD_TOO_LARGE")
                    && (read = socket.getInputStream().read(buffer)) != -1; ) {
                answer.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }

            assertThat(answer.toString()).startsWith("HTTP/1.1 413").contains("\"code\":\"UPLOAD_TOO_LARGE\"");
        }
        assertThat(Uploads.runs(jdbc)).isZero();
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("AC-2.9 A valid body of exactly 20,971,520 bytes is accepted")
    void aBodyAtTheLimitIsAccepted() throws Exception {
        Answer answer = http.sendBytes("POST", Uploads.PATH, padded(CiSample.JSON, LIMIT), "application/json", true,
                "Authorization", "Bearer " + t1);

        assertThat(answer.status()).as(answer.body()).isEqualTo(201);
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("AC-2.9 The CI sample as text/plain is 415 UNSUPPORTED_MEDIA_TYPE")
    void textPlainIsUnsupported() throws Exception {
        Answer answer = http.sendBytes("POST", Uploads.PATH, CiSample.JSON.getBytes(StandardCharsets.UTF_8),
                "text/plain", false, "Authorization", "Bearer " + t1);

        answer.assertProblem(415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(Uploads.runs(jdbc)).isZero();
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("AC-2.9 A body with 5,001 scenario elements is 422 UPLOAD_TOO_MANY_TEST_CASES")
    void tooManyTestCases() throws Exception {
        Answer answer = http.sendBytes("POST", Uploads.PATH, scenarios(5001, 0).getBytes(StandardCharsets.UTF_8),
                "application/json", false, "Authorization", "Bearer " + t1);

        answer.assertProblem(422, "UPLOAD_TOO_MANY_TEST_CASES");
        assertThat(Uploads.runs(jdbc)).isZero();
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("T7 stop condition: 5 uploads of ~20 MiB with 5,000 test cases each take at most 5 s at p95")
    void anUploadNearTheLimitIsFastEnough() throws Exception {
        String json = scenarios(5000, 3700);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertThat(body.length).isBetween(LIMIT - 2 * 1024 * 1024, LIMIT);
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            Answer answer = http.sendBytes("POST", Uploads.PATH, body, "application/json", false,
                    "Authorization", "Bearer " + t1);
            millis.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
            assertThat(answer.status()).as(answer.body()).isEqualTo(201);
        }
        long p95 = millis.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(p95).as("upload timings in ms: %s", millis).isLessThanOrEqualTo(5000);
    }

    /** The JSON text padded with trailing spaces (valid JSON) to exactly {@code size} bytes. */
    private static byte[] padded(String json, int size) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(text, size);
        Arrays.fill(body, text.length, size, (byte) ' ');
        return body;
    }

    /** One feature with {@code count} scenarios of 3 steps; the failing step has an error of {@code errorChars}. */
    private static String scenarios(int count, int errorChars) {
        String error = "e".repeat(errorChars);
        StringBuilder json = new StringBuilder("[{\"uri\":\"features/load.feature\",\"name\":\"Load\",\"elements\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"type\":\"scenario\",\"name\":\"Scenario ").append(i).append("\",\"line\":").append(i + 1)
                    .append(",\"tags\":[{\"name\":\"@load\"}],\"steps\":[")
                    .append("{\"keyword\":\"Given \",\"name\":\"a step\",\"result\":{\"status\":\"passed\","
                            + "\"duration\":1000000}},")
                    .append("{\"keyword\":\"When \",\"name\":\"another step\",\"result\":{\"status\":\"passed\","
                            + "\"duration\":2000000}},")
                    .append("{\"keyword\":\"Then \",\"name\":\"a check\",\"result\":{\"status\":\"failed\","
                            + "\"duration\":3000000,\"error_message\":\"").append(error).append("\"}}]}");
        }
        return json.append("]}]").toString();
    }
}
