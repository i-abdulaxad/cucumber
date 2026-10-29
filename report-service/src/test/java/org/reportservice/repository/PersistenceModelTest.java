package org.reportservice.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;
import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;
import org.reportservice.model.Build;
import org.reportservice.model.Environment;
import org.reportservice.model.StepResult;
import org.reportservice.model.TestCaseResult;
import org.reportservice.model.TestRun;
import org.reportservice.model.UploadToken;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D14, database pattern items 7, 9 and 11: every entity is stored and read back unchanged through its repository,
 * including the jsonb snapshots (tags, data tables), the {@code char(64)} token hash, enums by name, and instants with
 * microsecond precision.
 */
@ReportServiceIntegrationTest
class PersistenceModelTest {

    private static final Instant AT = Instant.parse("2026-10-29T09:30:00.123456Z");
    private static final UUID PROJECT = UUID.fromString("3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13");
    private static final UUID USER = UUID.fromString("5a2d8c1e-9f4b-4e7a-b3c6-0d1f2e3a4b5c");

    @Autowired
    private EnvironmentRepository environments;
    @Autowired
    private BuildRepository builds;
    @Autowired
    private UploadTokenRepository tokens;
    @Autowired
    private TestRunRepository runs;
    @Autowired
    private TestCaseResultRepository results;
    @Autowired
    private StepResultRepository steps;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        jdbc.update("DELETE FROM step_results");
        jdbc.update("DELETE FROM test_case_results");
        jdbc.update("DELETE FROM test_runs");
        jdbc.update("DELETE FROM upload_tokens");
        jdbc.update("DELETE FROM builds");
        jdbc.update("DELETE FROM environments");
    }

    @Test
    @DisplayName("D14: a run with its environment, build, token, result and steps is read back unchanged")
    void everyEntityRoundTrips() {
        // Given
        Environment environment = new Environment();
        environment.setId(UUID.randomUUID());
        environment.setProjectId(PROJECT);
        environment.setName("staging");
        environment.setCreatedAt(AT);
        environment.setUpdatedAt(AT);
        environments.save(environment);

        Build build = new Build();
        build.setId(UUID.randomUUID());
        build.setProjectId(PROJECT);
        build.setName("1.4.2+517");
        build.setBranch("main");
        build.setCommitSha("9fceb02");
        build.setCiUrl("https://ci.example.com/1");
        build.setCreatedAt(AT);
        build.setUpdatedAt(AT);
        builds.save(build);

        UploadToken token = new UploadToken();
        token.setId(UUID.randomUUID());
        token.setProjectId(PROJECT);
        token.setName("GitHub Actions");
        token.setTokenHash("a".repeat(64));
        token.setTokenPrefix("rct_abcdefgh");
        token.setCreatedBy(USER);
        token.setCreatedAt(AT);
        token.setUpdatedAt(AT);
        tokens.save(token);

        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setProjectId(PROJECT);
        run.setName("Nightly");
        run.setSource(TestRunSource.AUTOMATED);
        run.setStatus(TestRunStatus.COMPLETED);
        run.setEnvironmentId(environment.getId());
        run.setBuildId(build.getId());
        run.setUploadTokenId(token.getId());
        run.setFinishedAt(AT);
        run.setCreatedAt(AT);
        run.setUpdatedAt(AT);
        runs.save(run);

        TestCaseResult result = new TestCaseResult();
        result.setId(UUID.randomUUID());
        result.setTestRunId(run.getId());
        result.setPosition(1);
        result.setFeaturePath("features/checkout/payment.feature");
        result.setFeatureName("Payment");
        result.setScenarioName("Pay by card ✓");
        result.setTags(List.of("@checkout", "@smoke"));
        result.setLine(12);
        result.setStatus(ExecutionStatus.PASSED);
        result.setAttempt(1);
        result.setDurationMs(6L);
        result.setCreatedAt(AT);
        result.setUpdatedAt(AT);
        results.save(result);

        StepResult step = new StepResult();
        step.setId(UUID.randomUUID());
        step.setTestCaseResultId(result.getId());
        step.setPosition(1);
        step.setKind(StepResultKind.STEP);
        step.setKeywordText("Given");
        step.setStepText("a basket with these items:");
        step.setDataTable(List.of(List.of("item", "price"), List.of("Mug", "8.00")));
        step.setDocString("Total: 8.00");
        step.setStatus(ExecutionStatus.PASSED);
        step.setDurationMs(1L);
        step.setCreatedAt(AT);
        step.setUpdatedAt(AT);
        steps.save(step);

        // When
        TestRun storedRun = runs.findById(run.getId()).orElseThrow();
        TestCaseResult storedResult = results.findById(result.getId()).orElseThrow();
        StepResult storedStep = steps.findById(step.getId()).orElseThrow();
        UploadToken storedToken = tokens.findById(token.getId()).orElseThrow();

        // Then
        assertThat(storedRun.getSource()).isEqualTo(TestRunSource.AUTOMATED);
        assertThat(storedRun.getStatus()).isEqualTo(TestRunStatus.COMPLETED);
        assertThat(storedRun.getFinishedAt()).isEqualTo(AT);
        assertThat(storedRun.getCreatedBy()).isNull();
        assertThat(storedResult.getTags()).containsExactly("@checkout", "@smoke");
        assertThat(storedResult.getScenarioName()).isEqualTo("Pay by card ✓");
        assertThat(storedResult.getFeatureId()).isNull();
        assertThat(storedResult.getFeatureVersion()).isNull();
        assertThat(storedStep.getDataTable()).containsExactly(List.of("item", "price"), List.of("Mug", "8.00"));
        assertThat(storedStep.getKind()).isEqualTo(StepResultKind.STEP);
        assertThat(storedToken.getTokenHash()).isEqualTo("a".repeat(64));
        assertThat(storedToken.getCreatedAt()).isEqualTo(AT);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(tags) FROM test_case_results", String.class))
                .isEqualTo("array");
        assertThat(jdbc.queryForObject("SELECT data_table::text FROM step_results", String.class))
                .isEqualTo("[[\"item\", \"price\"], [\"Mug\", \"8.00\"]]");
    }
}
