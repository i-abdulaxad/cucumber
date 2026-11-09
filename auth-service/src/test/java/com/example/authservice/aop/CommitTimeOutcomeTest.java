package com.example.authservice.aop;

import com.example.authservice.aop.RequestLog.AspectLine;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Locale;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D17 item 1: the aspect wraps the transaction advice, so an exception thrown at commit is the call's outcome. Creating
 * an organization only inserts its row when the transaction flushes at commit; a test-only statement inspector makes
 * that insert fail.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CommitTimeOutcomeTest {

    private static final String ASPECT_LOGGER = "com.example.authservice.aop";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private LoggingSystem loggingSystem;

    @Autowired
    private FailingOrganizationInsert failingInsert;

    @BeforeEach
    void seedAcme() {
        fixture.seed();
    }

    @AfterEach
    void restore() {
        failingInsert.failing = false;
        loggingSystem.setLogLevel(ASPECT_LOGGER, null);
    }

    @Test
    @DisplayName("D17 an exception thrown when the transaction commits is the outcome the aspect logs")
    void anExceptionAtCommitIsTheOutcomeOfTheCall(CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ana@example.com");
        loggingSystem.setLogLevel(ASPECT_LOGGER, LogLevel.DEBUG);
        failingInsert.failing = true;
        int start = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(post("/api/orgs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Commit QA\"}"))
                .andReturn();

        // Then
        assertThat(failingInsert.failures).as("inserts made to fail").isEqualTo(1);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        List<AspectLine> exits = RequestLog.since(output, start).aspectLines().stream()
                .filter(line -> line.message().startsWith("Leaving OrganizationServiceImpl.createOrganization:"))
                .toList();
        assertThat(exits).singleElement().satisfies(line -> assertThat(line.message())
                .matches("Leaving OrganizationServiceImpl\\.createOrganization: threw \\w+ in \\d+ ms"));
    }

    /**
     * Fails the insert into {@code organizations}, which Hibernate runs when the transaction flushes at commit.
     */
    static class FailingOrganizationInsert implements StatementInspector {

        private volatile boolean failing;
        private volatile int failures;

        @Override
        public String inspect(String sql) {
            if (failing && sql.toLowerCase(Locale.ROOT).startsWith("insert into organizations")) {
                failures++;
                throw new IllegalStateException("insert refused by the test");
            }
            return sql;
        }
    }

    @TestConfiguration
    static class FailingInsertConfiguration {

        @Bean
        FailingOrganizationInsert failingOrganizationInsert() {
            return new FailingOrganizationInsert();
        }

        @Bean
        HibernatePropertiesCustomizer failingOrganizationInsertInspector(FailingOrganizationInsert inspector) {
            return properties -> properties.put(JdbcSettings.STATEMENT_INSPECTOR, inspector);
        }
    }
}
