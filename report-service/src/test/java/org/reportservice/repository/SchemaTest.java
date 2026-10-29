package org.reportservice.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-7.3 (T2, D14, database pattern item 17): the schema that Flyway's V1 creates is exactly the phase-1 part of the
 * ERD's {@code report_service} group ({@code docs/erd/cucumber.dbml}), without {@code attachments}: tables, columns
 * with types and nullability, no column default, primary keys, {@code fk_} foreign keys with ON DELETE RESTRICT, enum
 * CHECK values (ERD-D12), and the named unique constraints and indexes.
 */
@ReportServiceIntegrationTest
class SchemaTest {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private static final List<String> EXECUTION_STATUSES =
            List.of("PASSED", "FAILED", "SKIPPED", "PENDING", "UNDEFINED", "AMBIGUOUS", "UNKNOWN");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3 The tables are exactly the six phase-1 tables, plus Flyway's history; no attachments")
    void theTablesAreThePhaseOneTables() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", String.class))
                .containsExactlyInAnyOrder("environments", "builds", "upload_tokens", "test_runs",
                        "test_case_results", "step_results", "flyway_schema_history");
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3 Every column has the ERD's type and nullability, and no column has a database default")
    void everyColumnHasTheErdTypeAndNullability() {
        List<String> columns = jdbc.queryForList("SELECT table_name || '.' || column_name || ' ' || "
                + "CASE WHEN character_maximum_length IS NULL THEN data_type "
                + "ELSE data_type || '(' || character_maximum_length || ')' END || "
                + "CASE is_nullable WHEN 'YES' THEN ' null' ELSE ' not null' END "
                + "FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name <> 'flyway_schema_history' ORDER BY 1", String.class);

        assertThat(columns).containsExactly(
                "builds.branch character varying(255) null",
                "builds.ci_url character varying(2048) null",
                "builds.commit_sha character varying(64) null",
                "builds.created_at timestamp with time zone not null",
                "builds.id uuid not null",
                "builds.name character varying(255) not null",
                "builds.project_id uuid not null",
                "builds.updated_at timestamp with time zone not null",
                "environments.created_at timestamp with time zone not null",
                "environments.description text null",
                "environments.id uuid not null",
                "environments.name character varying(100) not null",
                "environments.project_id uuid not null",
                "environments.updated_at timestamp with time zone not null",
                "step_results.created_at timestamp with time zone not null",
                "step_results.data_table jsonb null",
                "step_results.doc_string text null",
                "step_results.duration_ms bigint null",
                "step_results.error_message text null",
                "step_results.id uuid not null",
                "step_results.keyword_text text null",
                "step_results.kind character varying(32) not null",
                "step_results.position integer not null",
                "step_results.status character varying(32) not null",
                "step_results.step_id uuid null",
                "step_results.step_text text null",
                "step_results.test_case_result_id uuid not null",
                "step_results.updated_at timestamp with time zone not null",
                "test_case_results.attempt integer not null",
                "test_case_results.comment text null",
                "test_case_results.created_at timestamp with time zone not null",
                "test_case_results.duration_ms bigint null",
                "test_case_results.example_row_id uuid null",
                "test_case_results.executed_by uuid null",
                "test_case_results.feature_id uuid null",
                "test_case_results.feature_name text not null",
                "test_case_results.feature_path text not null",
                "test_case_results.feature_version integer null",
                "test_case_results.finished_at timestamp with time zone null",
                "test_case_results.id uuid not null",
                "test_case_results.line integer null",
                "test_case_results.position integer not null",
                "test_case_results.scenario_id uuid null",
                "test_case_results.scenario_name text not null",
                "test_case_results.started_at timestamp with time zone null",
                "test_case_results.status character varying(32) not null",
                "test_case_results.tags jsonb not null",
                "test_case_results.test_run_id uuid not null",
                "test_case_results.updated_at timestamp with time zone not null",
                "test_runs.build_id uuid null",
                "test_runs.created_at timestamp with time zone not null",
                "test_runs.created_by uuid null",
                "test_runs.environment_id uuid null",
                "test_runs.finished_at timestamp with time zone null",
                "test_runs.id uuid not null",
                "test_runs.name character varying(255) not null",
                "test_runs.project_id uuid not null",
                "test_runs.source character varying(32) not null",
                "test_runs.started_at timestamp with time zone null",
                "test_runs.status character varying(32) not null",
                "test_runs.tag_expression character varying(1000) null",
                "test_runs.updated_at timestamp with time zone not null",
                "test_runs.upload_token_id uuid null",
                "upload_tokens.created_at timestamp with time zone not null",
                "upload_tokens.created_by uuid not null",
                "upload_tokens.expires_at timestamp with time zone null",
                "upload_tokens.id uuid not null",
                "upload_tokens.last_used_at timestamp with time zone null",
                "upload_tokens.name character varying(100) not null",
                "upload_tokens.project_id uuid not null",
                "upload_tokens.revoked_at timestamp with time zone null",
                "upload_tokens.revoked_by uuid null",
                "upload_tokens.token_hash character(64) not null",
                "upload_tokens.token_prefix character varying(16) not null",
                "upload_tokens.updated_at timestamp with time zone not null");
        assertThat(jdbc.queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' "
                + "AND column_default IS NOT NULL", String.class)).as("columns with a database default").isEmpty();
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3 Primary keys, fk_ foreign keys with ON DELETE RESTRICT, and unique constraints, by name")
    void keysAndUniqueConstraintsHaveTheErdNamesAndRules() {
        assertThat(constraints("p", "f", "u")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("pk_environments", "PRIMARY KEY (id)"),
                Map.entry("pk_builds", "PRIMARY KEY (id)"),
                Map.entry("pk_upload_tokens", "PRIMARY KEY (id)"),
                Map.entry("pk_test_runs", "PRIMARY KEY (id)"),
                Map.entry("pk_test_case_results", "PRIMARY KEY (id)"),
                Map.entry("pk_step_results", "PRIMARY KEY (id)"),
                Map.entry("fk_test_runs_environment_id",
                        "FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE RESTRICT"),
                Map.entry("fk_test_runs_build_id", "FOREIGN KEY (build_id) REFERENCES builds(id) ON DELETE RESTRICT"),
                Map.entry("fk_test_runs_upload_token_id",
                        "FOREIGN KEY (upload_token_id) REFERENCES upload_tokens(id) ON DELETE RESTRICT"),
                Map.entry("fk_test_case_results_test_run_id",
                        "FOREIGN KEY (test_run_id) REFERENCES test_runs(id) ON DELETE RESTRICT"),
                Map.entry("fk_step_results_test_case_result_id",
                        "FOREIGN KEY (test_case_result_id) REFERENCES test_case_results(id) ON DELETE RESTRICT"),
                Map.entry("uk_environments_project_id_name", "UNIQUE (project_id, name)"),
                Map.entry("uk_builds_project_id_name", "UNIQUE (project_id, name)"),
                Map.entry("uk_upload_tokens_token_hash", "UNIQUE (token_hash)"),
                Map.entry("uk_test_case_results_test_run_id_position", "UNIQUE (test_run_id, \"position\")"),
                Map.entry("uk_step_results_test_case_result_id_position",
                        "UNIQUE (test_case_result_id, \"position\")")));
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3, ERD-D12: every enum column has a CHECK listing exactly the ERD's values, and no other CHECK")
    void enumColumnsHaveChecksWithTheErdValues() {
        Map<String, String> checks = constraints("c");

        assertThat(checks).containsOnlyKeys("ck_test_runs_source_values", "ck_test_runs_status_values",
                "ck_test_case_results_status_values", "ck_step_results_kind_values", "ck_step_results_status_values");
        assertThat(checks.get("ck_test_runs_source_values")).contains("source");
        assertThat(values(checks.get("ck_test_runs_source_values"))).containsExactly("MANUAL", "AUTOMATED");
        assertThat(checks.get("ck_test_runs_status_values")).contains("status");
        assertThat(values(checks.get("ck_test_runs_status_values")))
                .containsExactly("PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELLED");
        assertThat(checks.get("ck_test_case_results_status_values")).contains("status");
        assertThat(values(checks.get("ck_test_case_results_status_values")))
                .containsExactlyElementsOf(EXECUTION_STATUSES);
        assertThat(checks.get("ck_step_results_kind_values")).contains("kind");
        assertThat(values(checks.get("ck_step_results_kind_values")))
                .containsExactly("STEP", "BEFORE_HOOK", "AFTER_HOOK");
        assertThat(checks.get("ck_step_results_status_values")).contains("status");
        assertThat(values(checks.get("ck_step_results_status_values"))).containsExactlyElementsOf(EXECUTION_STATUSES);
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3 The indexes are exactly the ERD's, by name")
    void theIndexesAreThoseOfTheErd() {
        Map<String, String> indexes = jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'").stream()
                .collect(Collectors.toMap(row -> (String) row.get("indexname"), row -> (String) row.get("indexdef")));

        assertThat(indexes).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("pk_environments",
                        "CREATE UNIQUE INDEX pk_environments ON public.environments USING btree (id)"),
                Map.entry("pk_builds", "CREATE UNIQUE INDEX pk_builds ON public.builds USING btree (id)"),
                Map.entry("pk_upload_tokens",
                        "CREATE UNIQUE INDEX pk_upload_tokens ON public.upload_tokens USING btree (id)"),
                Map.entry("pk_test_runs", "CREATE UNIQUE INDEX pk_test_runs ON public.test_runs USING btree (id)"),
                Map.entry("pk_test_case_results",
                        "CREATE UNIQUE INDEX pk_test_case_results ON public.test_case_results USING btree (id)"),
                Map.entry("pk_step_results",
                        "CREATE UNIQUE INDEX pk_step_results ON public.step_results USING btree (id)"),
                Map.entry("uk_environments_project_id_name",
                        "CREATE UNIQUE INDEX uk_environments_project_id_name ON public.environments "
                                + "USING btree (project_id, name)"),
                Map.entry("uk_builds_project_id_name",
                        "CREATE UNIQUE INDEX uk_builds_project_id_name ON public.builds "
                                + "USING btree (project_id, name)"),
                Map.entry("uk_upload_tokens_token_hash",
                        "CREATE UNIQUE INDEX uk_upload_tokens_token_hash ON public.upload_tokens "
                                + "USING btree (token_hash)"),
                Map.entry("ix_upload_tokens_project_id",
                        "CREATE INDEX ix_upload_tokens_project_id ON public.upload_tokens USING btree (project_id)"),
                Map.entry("ix_test_runs_project_id_created_at",
                        "CREATE INDEX ix_test_runs_project_id_created_at ON public.test_runs "
                                + "USING btree (project_id, created_at)"),
                Map.entry("ix_test_runs_environment_id",
                        "CREATE INDEX ix_test_runs_environment_id ON public.test_runs USING btree (environment_id)"),
                Map.entry("ix_test_runs_build_id",
                        "CREATE INDEX ix_test_runs_build_id ON public.test_runs USING btree (build_id)"),
                Map.entry("ix_test_runs_upload_token_id",
                        "CREATE INDEX ix_test_runs_upload_token_id ON public.test_runs USING btree (upload_token_id)"),
                Map.entry("uk_test_case_results_test_run_id_position",
                        "CREATE UNIQUE INDEX uk_test_case_results_test_run_id_position ON public.test_case_results "
                                + "USING btree (test_run_id, \"position\")"),
                Map.entry("ix_test_case_results_scenario_id",
                        "CREATE INDEX ix_test_case_results_scenario_id ON public.test_case_results "
                                + "USING btree (scenario_id)"),
                Map.entry("uk_step_results_test_case_result_id_position",
                        "CREATE UNIQUE INDEX uk_step_results_test_case_result_id_position ON public.step_results "
                                + "USING btree (test_case_result_id, \"position\")")));
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3, item 10: every foreign-key column leads some index")
    void everyForeignKeyColumnLeadsAnIndex() {
        List<String> unindexed = jdbc.queryForList("SELECT c.conname FROM pg_constraint c "
                        + "WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace AND NOT EXISTS ("
                        + "SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])",
                String.class);

        assertThat(unindexed).isEmpty();
    }

    /** Constraints of the given types ({@code pg_constraint.contype}), by name, as PostgreSQL prints them. */
    private Map<String, String> constraints(String... types) {
        return jdbc.queryForList("SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint "
                        + "WHERE connamespace = 'public'::regnamespace AND contype::text IN ('"
                        + String.join("', '", types) + "') AND conrelid <> 'flyway_schema_history'::regclass")
                .stream()
                .collect(Collectors.toMap(row -> (String) row.get("conname"), row -> (String) row.get("def")));
    }

    private static List<String> values(String check) {
        return QUOTED.matcher(check).results().map(result -> result.group(1)).toList();
    }
}
