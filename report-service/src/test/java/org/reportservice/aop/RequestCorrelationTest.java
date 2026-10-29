package org.reportservice.aop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.RequestLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-7.2, T3's part (D17): with report-service's loggers at DEBUG and {@code X-Request-Id: r-77}, every line of a
 * request carries {@code requestId=r-77} and the caller's user id, the logging aspect writes one outcome line per
 * service call, and no line contains a JWT, a body value or the service key. The upload and recording parts come
 * with T7 and T9.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationTest {

    private static final String SERVICE_LOGGERS = "org.reportservice";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetTheStub() {
        AuthServiceStub.get().reset();
    }

    @Test
    @Tag("AC-7.2")
    @DisplayName("AC-7.2 Every line of a request has requestId r-77 and the user id; one outcome line per call")
    void everyLineOfARequestIsCorrelatedAndContentFree(CapturedOutput output) throws Exception {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        LogLevel found = configuredLevel(logging);
        int logStart = output.getAll().length();
        try {
            logging.setLogLevel(SERVICE_LOGGERS, LogLevel.DEBUG);

            assertThat(mockMvc.perform(get("/api/projects/" + CHECKOUT + "/probe")
                    .header(HttpHeaders.AUTHORIZATION, DAN.bearer()).header("X-Request-Id", "r-77"))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mockMvc.perform(post("/api/projects/" + CHECKOUT + "/probe")
                    .header(HttpHeaders.AUTHORIZATION, GUS.bearer()).header("X-Request-Id", "r-77")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Release secret-QA\"}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        } finally {
            // Log levels are JVM-global: put back what the test found
            logging.setLogLevel(SERVICE_LOGGERS, found);
        }

        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.entries()).isNotEmpty().allSatisfy(line -> assertThat(line).contains("requestId=r-77"));
        assertThat(log.entries()).filteredOn(line -> line.contains("ProbeService.read")).isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("userId=" + DAN.id()));
        assertThat(log.aspectLines()).filteredOn(line -> line.message().startsWith("Leaving ")).extracting(
                line -> line.message().replaceAll(" in \\d+ ms$", "")).containsExactly(
                "Leaving ProbeService.read: returned", "Leaving ProbeService.write: returned");
        assertThat(log.lines()).noneMatch(line -> line.contains(DAN.token()) || line.contains(GUS.token())
                || line.contains("secret-QA") || line.contains("Authorization"));
    }

    private static LogLevel configuredLevel(LoggingSystem logging) {
        LoggerConfiguration configuration = logging.getLoggerConfiguration(SERVICE_LOGGERS);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }
}
