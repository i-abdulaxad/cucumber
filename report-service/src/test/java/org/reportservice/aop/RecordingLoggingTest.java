package org.reportservice.aop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.RunData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AC-7.2's recording part and AC-7.4's comment part (D17, RE8, RE9): with the root logger at DEBUG and
 * {@code X-Request-Id: r-77}, Hal records a result with a comment. Every line of the request carries r-77, the
 * recording service has one outcome line, and no line contains the comment or Hal's token.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RecordingLoggingTest {

    private static final String COMMENT = "Voucher accepted QA-comment-8841";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-7.2")
    @Tag("AC-7.4")
    @DisplayName("AC-7.2 A recording with a comment at root DEBUG: requestId r-77 on every line, no comment, no token")
    void aRecordingLogsNoComment(CapturedOutput output) throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        LoggerConfiguration root = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel rootFound = root == null ? null : root.getConfiguredLevel();
        int logStart = output.getAll().length();
        try {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);

            assertThat(mockMvc.perform(put("/api/projects/" + CHECKOUT + "/runs/" + run.id() + "/results/"
                            + run.results().get(0)).header(HttpHeaders.AUTHORIZATION, HAL.bearer())
                    .header("X-Request-Id", "r-77").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"FAILED\",\"comment\":\"" + COMMENT + "\"}")).andReturn().getResponse()
                    .getStatus()).isEqualTo(200);
        } finally {
            // Log levels are JVM-global: put back what the test found
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootFound);
        }

        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.entries()).filteredOn(line -> line.contains("org.reportservice")
                || line.contains("o.r.")).isNotEmpty().allSatisfy(line -> assertThat(line).contains("requestId=r-77"));
        assertThat(log.aspectLines()).filteredOn(line -> line.message().startsWith("Leaving ")).extracting(
                line -> line.message().replaceAll(" in \\d+ ms$", ""))
                .containsExactly("Leaving RunRecordingServiceImpl.record: returned");
        assertThat(log.lines()).noneMatch(line -> line.contains("QA-comment-8841") || line.contains(HAL.token()));
    }
}
