package org.reportservice.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-7.3, database pattern item 17: each V1 constraint, checked with direct SQL. Unique names are exact and
 * case-sensitive (D11); enum columns accept only the Java constants (ERD-D12); foreign keys restrict deletes.
 */
@ReportServiceIntegrationTest
class DatabaseConstraintsTest {

    private static final String NOW = "TIMESTAMPTZ '2026-10-29 09:30:00Z'";

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID project = UUID.randomUUID();

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
    @Tag("AC-7.3")
    @DisplayName("AC-7.3, D11: environment and build names are unique per project, exactly and case-sensitively")
    void namesAreUniquePerProjectAndCaseSensitive() {
        environment("staging");
        build("1.4.2+517");

        assertThatThrownBy(() -> environment("staging")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> build("1.4.2+517")).isInstanceOf(DuplicateKeyException.class);
        assertThatCode(() -> environment("Staging")).doesNotThrowAnyException();
        assertThatCode(() -> build("1.4.2+518")).doesNotThrowAnyException();
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3, D4: a token hash is stored once")
    void tokenHashesAreUnique() {
        token("b".repeat(64));

        assertThatThrownBy(() -> token("b".repeat(64))).isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest(name = "AC-7.3 test_runs.{0} rejects a value outside the enum")
    @ValueSource(strings = {"source", "status"})
    @Tag("AC-7.3")
    void runEnumsAcceptOnlyTheirConstants(String column) {
        String source = column.equals("source") ? "'Manual'" : "'MANUAL'";
        String status = column.equals("status") ? "'DONE'" : "'PLANNED'";

        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_runs (id, project_id, name, source, status, "
                + "created_at, updated_at) VALUES (?, ?, 'R', " + source + ", " + status + ", " + NOW + ", " + NOW
                + ")", UUID.randomUUID(), project)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3: result and step enums accept only their constants; positions are unique per parent")
    void resultAndStepRulesHold() {
        UUID run = run(null);
        UUID result = result(run, 1, "PASSED");

        assertThatThrownBy(() -> result(run, 2, "passed")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> result(run, 1, "FAILED")).isInstanceOf(DuplicateKeyException.class);
        step(result, 1, "STEP");
        assertThatThrownBy(() -> step(result, 2, "HOOK")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> step(result, 1, "AFTER_HOOK")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3: every foreign key restricts deletes of the referenced row")
    void foreignKeysRestrictDeletes() {
        UUID environment = environment("staging");
        UUID run = run(environment);
        UUID result = result(run, 1, "PASSED");
        step(result, 1, "STEP");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM environments WHERE id = ?", environment))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM test_runs WHERE id = ?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM test_case_results WHERE id = ?", result))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID environment(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO environments (id, project_id, name, created_at, updated_at) VALUES (?, ?, ?, "
                + NOW + ", " + NOW + ")", id, project, name);
        return id;
    }

    private void build(String name) {
        jdbc.update("INSERT INTO builds (id, project_id, name, created_at, updated_at) VALUES (?, ?, ?, " + NOW + ", "
                + NOW + ")", UUID.randomUUID(), project, name);
    }

    private void token(String hash) {
        jdbc.update("INSERT INTO upload_tokens (id, project_id, name, token_hash, token_prefix, created_by, "
                + "created_at, updated_at) VALUES (?, ?, 'CI', ?, 'rct_abcdefgh', ?, " + NOW + ", " + NOW + ")",
                UUID.randomUUID(), project, hash, UUID.randomUUID());
    }

    private UUID run(UUID environment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO test_runs (id, project_id, name, source, status, environment_id, created_at, "
                + "updated_at) VALUES (?, ?, 'R', 'MANUAL', 'PLANNED', ?, " + NOW + ", " + NOW + ")",
                id, project, environment);
        return id;
    }

    private UUID result(UUID run, int position, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO test_case_results (id, test_run_id, position, feature_path, feature_name, "
                + "scenario_name, tags, status, attempt, created_at, updated_at) VALUES (?, ?, ?, 'a.feature', '', "
                + "'S', '[]'::jsonb, ?, 1, " + NOW + ", " + NOW + ")", id, run, position, status);
        return id;
    }

    private void step(UUID result, int position, String kind) {
        jdbc.update("INSERT INTO step_results (id, test_case_result_id, position, kind, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, 'UNKNOWN', " + NOW + ", " + NOW + ")",
                UUID.randomUUID(), result, position, kind);
    }
}
