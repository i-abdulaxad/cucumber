package org.reportservice.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs stored directly for tests of recording and reading (US-4, US-5): a run of the project with {@code results}
 * UNKNOWN results in feature {@code features/checkout/payment.feature} ("Payment"), each with one UNKNOWN step.
 */
public final class RunData {

    private RunData() {
    }

    /** A stored run and its result ids, by position. */
    public record Run(UUID id, List<UUID> results) {
    }

    public static Run manual(JdbcTemplate jdbc, UUID projectId, String status, int results) {
        return run(jdbc, projectId, "MANUAL", status, ReportsFixture.GUS.id(), null, results, Instant.now());
    }

    public static Run automated(JdbcTemplate jdbc, UUID projectId, int results) {
        UUID token = ReportData.token(jdbc, projectId, "CI", ReportData.randomToken(), ReportsFixture.CAI.id(),
                Instant.now(), null, null);
        return run(jdbc, projectId, "AUTOMATED", "COMPLETED", null, token, results, Instant.now());
    }

    public static Run run(JdbcTemplate jdbc, UUID projectId, String source, String status, UUID createdBy,
                          UUID uploadTokenId, int results, Instant createdAt) {
        UUID runId = UUID.randomUUID();
        Timestamp at = Timestamp.from(createdAt);
        Timestamp finished = "COMPLETED".equals(status) || "CANCELLED".equals(status) ? at : null;
        jdbc.update("INSERT INTO test_runs (id, project_id, name, source, status, upload_token_id, created_by, "
                        + "finished_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runId, projectId, "Run " + createdAt, source, status, uploadTokenId, createdBy, finished, at, at);
        List<UUID> ids = new ArrayList<>();
        for (int position = 1; position <= results; position++) {
            UUID resultId = UUID.randomUUID();
            jdbc.update("INSERT INTO test_case_results (id, test_run_id, position, feature_path, feature_name, "
                            + "scenario_name, tags, status, attempt, created_at, updated_at) VALUES (?, ?, ?, "
                            + "'features/checkout/payment.feature', 'Payment', ?, '[\"@checkout\"]'::jsonb, "
                            + "'UNKNOWN', 1, ?, ?)", resultId, runId, position, "Scenario " + position, at, at);
            jdbc.update("INSERT INTO step_results (id, test_case_result_id, position, kind, keyword_text, step_text, "
                            + "status, created_at, updated_at) "
                            + "VALUES (?, ?, 1, 'STEP', 'Given', 'a step', 'UNKNOWN', ?, ?)",
                    UUID.randomUUID(), resultId, at, at);
            ids.add(resultId);
        }
        return new Run(runId, ids);
    }
}
