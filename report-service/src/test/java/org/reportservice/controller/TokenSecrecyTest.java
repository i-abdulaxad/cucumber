package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-1.10, T5's part (D4, D17): with report-service's and Spring's loggers at DEBUG (web, security, transactions),
 * creating, listing and revoking a token logs neither the token, its prefix, its hash nor an Authorization value.
 * Open (decision at review point B): with the root logger at DEBUG, Hibernate's {@code org.hibernate.orm.core}
 * "Listing entities" lines print entity state, the token hash and prefix included; pinning that logger needs an
 * approved change to {@code application.yaml}. The CI upload part of AC-1.10 comes with T7.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TokenSecrecyTest {

    private static final String SERVICE_LOGGERS = "org.reportservice";
    private static final String SPRING_LOGGERS = "org.springframework";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-1.10")
    @DisplayName("AC-1.10 Create, list, revoke with service and Spring DEBUG: no token, prefix, hash, Authorization")
    void tokensNeverReachTheLog(CapturedOutput output) throws Exception {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        LogLevel serviceFound = configuredLevel(logging, SERVICE_LOGGERS);
        LogLevel springFound = configuredLevel(logging, SPRING_LOGGERS);
        int logStart = output.getAll().length();
        String token;
        try {
            logging.setLogLevel(SERVICE_LOGGERS, LogLevel.DEBUG);
            logging.setLogLevel(SPRING_LOGGERS, LogLevel.DEBUG);

            MvcResult created = mockMvc.perform(post("/api/projects/" + CHECKOUT + "/upload-tokens")
                    .header(HttpHeaders.AUTHORIZATION, CAI.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"GitHub Actions\"}")).andReturn();
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            token = JsonPath.read(body(created), "$.token");
            String id = JsonPath.read(body(created), "$.id");
            assertThat(mockMvc.perform(get("/api/projects/" + CHECKOUT + "/upload-tokens")
                    .header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(mockMvc.perform(post("/api/projects/" + CHECKOUT + "/upload-tokens/" + id + "/revoke")
                    .header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
        } finally {
            // Log levels are JVM-global: put back what the test found
            logging.setLogLevel(SERVICE_LOGGERS, serviceFound);
            logging.setLogLevel(SPRING_LOGGERS, springFound);
        }

        String log = output.getAll().substring(logStart);
        assertThat(log).contains(" DEBUG ");
        assertThat(log).doesNotContain(token, token.substring(0, 12), ReportData.sha256Hex(token), CAI.token(),
                "Authorization");
    }

    private static LogLevel configuredLevel(LoggingSystem logging, String logger) {
        LoggerConfiguration configuration = logging.getLoggerConfiguration(logger);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }
}
