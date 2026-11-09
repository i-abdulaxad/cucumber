package org.scenarioservice.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2, database pattern item 17: the schema that Flyway's V1 creates is exactly the ERD's {@code scenario_service}
 * group ({@code docs/erd/cucumber.dbml}): tables, columns with types and nullability, primary keys, foreign keys with
 * their delete rules (D14's named deviation), enum CHECK values (ERD-D12), unique constraints and indexes, including
 * the hashed, ICU-lower-cased partial path index and the hashed tag index (D10, D14).
 */
@ScenarioServiceIntegrationTest
class SchemaTest {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Item 17: the tables are the nine of the ERD's scenario_service group, plus Flyway's history")
    void theTablesAreThoseOfTheErd() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", String.class))
                .containsExactlyInAnyOrder("features", "feature_versions", "rules", "scenarios", "steps", "examples",
                        "example_rows", "tags", "tag_assignments", "flyway_schema_history");
    }

    @Test
    @DisplayName("Item 17: every column has the ERD's type and nullability, and no column has a database default")
    void everyColumnHasTheErdTypeAndNullability() {
        List<String> columns = jdbc.queryForList("SELECT table_name || '.' || column_name || ' ' || "
                + "CASE WHEN character_maximum_length IS NULL THEN data_type "
                + "ELSE data_type || '(' || character_maximum_length || ')' END || "
                + "CASE is_nullable WHEN 'YES' THEN ' null' ELSE ' not null' END "
                + "FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name <> 'flyway_schema_history' ORDER BY 1", String.class);

        assertThat(columns).containsExactly(
                "example_rows.cells jsonb not null",
                "example_rows.created_at timestamp with time zone not null",
                "example_rows.examples_id uuid not null",
                "example_rows.id uuid not null",
                "example_rows.line integer not null",
                "example_rows.position integer not null",
                "example_rows.updated_at timestamp with time zone not null",
                "examples.created_at timestamp with time zone not null",
                "examples.description text not null",
                "examples.header jsonb not null",
                "examples.id uuid not null",
                "examples.keyword_text text not null",
                "examples.line integer not null",
                "examples.name text not null",
                "examples.position integer not null",
                "examples.scenario_id uuid not null",
                "examples.updated_at timestamp with time zone not null",
                "feature_versions.content_hash character varying(64) not null",
                "feature_versions.created_at timestamp with time zone not null",
                "feature_versions.created_by uuid not null",
                "feature_versions.feature_id uuid not null",
                "feature_versions.id uuid not null",
                "feature_versions.message character varying(500) null",
                "feature_versions.source text not null",
                "feature_versions.version integer not null",
                "features.archived_at timestamp with time zone null",
                "features.created_at timestamp with time zone not null",
                "features.created_by uuid not null",
                "features.current_version integer not null",
                "features.description text not null",
                "features.id uuid not null",
                "features.language character varying(10) not null",
                "features.name text not null",
                "features.path character varying(1024) not null",
                "features.project_id uuid not null",
                "features.updated_at timestamp with time zone not null",
                "features.updated_by uuid not null",
                "rules.created_at timestamp with time zone not null",
                "rules.description text not null",
                "rules.feature_id uuid not null",
                "rules.id uuid not null",
                "rules.keyword_text text not null",
                "rules.line integer not null",
                "rules.name text not null",
                "rules.position integer not null",
                "rules.updated_at timestamp with time zone not null",
                "scenarios.created_at timestamp with time zone not null",
                "scenarios.description text not null",
                "scenarios.feature_id uuid not null",
                "scenarios.id uuid not null",
                "scenarios.keyword_text text not null",
                "scenarios.kind character varying(32) not null",
                "scenarios.line integer not null",
                "scenarios.name text not null",
                "scenarios.position integer not null",
                "scenarios.rule_id uuid null",
                "scenarios.updated_at timestamp with time zone not null",
                "steps.argument_type character varying(32) not null",
                "steps.created_at timestamp with time zone not null",
                "steps.data_table jsonb null",
                "steps.doc_string text null",
                "steps.doc_string_media_type text null",
                "steps.id uuid not null",
                "steps.keyword character varying(32) not null",
                "steps.keyword_text text not null",
                "steps.line integer not null",
                "steps.position integer not null",
                "steps.scenario_id uuid not null",
                "steps.step_text text not null",
                "steps.updated_at timestamp with time zone not null",
                "tag_assignments.created_at timestamp with time zone not null",
                "tag_assignments.feature_id uuid not null",
                "tag_assignments.id uuid not null",
                "tag_assignments.tag_id uuid not null",
                "tag_assignments.target_id uuid not null",
                "tag_assignments.target_type character varying(32) not null",
                "tags.created_at timestamp with time zone not null",
                "tags.id uuid not null",
                "tags.name text not null",
                "tags.project_id uuid not null",
                "tags.updated_at timestamp with time zone not null");
        assertThat(jdbc.queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' "
                + "AND column_default IS NOT NULL", String.class)).as("columns with a database default").isEmpty();
    }

    @Test
    @DisplayName("Item 17: primary keys, foreign keys with D14's delete rules, and unique constraints, by name")
    void keysAndUniqueConstraintsHaveTheErdNamesAndRules() {
        Map<String, String> constraints = constraints("p", "f", "u");

        assertThat(constraints).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("pk_features", "PRIMARY KEY (id)"),
                Map.entry("pk_feature_versions", "PRIMARY KEY (id)"),
                Map.entry("pk_rules", "PRIMARY KEY (id)"),
                Map.entry("pk_scenarios", "PRIMARY KEY (id)"),
                Map.entry("pk_steps", "PRIMARY KEY (id)"),
                Map.entry("pk_examples", "PRIMARY KEY (id)"),
                Map.entry("pk_example_rows", "PRIMARY KEY (id)"),
                Map.entry("pk_tags", "PRIMARY KEY (id)"),
                Map.entry("pk_tag_assignments", "PRIMARY KEY (id)"),
                Map.entry("fk_feature_versions_feature_id",
                        "FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE RESTRICT"),
                Map.entry("fk_rules_feature_id", "FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE"),
                Map.entry("fk_scenarios_feature_id",
                        "FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE"),
                Map.entry("fk_scenarios_rule_id", "FOREIGN KEY (rule_id) REFERENCES rules(id) ON DELETE CASCADE"),
                Map.entry("fk_steps_scenario_id",
                        "FOREIGN KEY (scenario_id) REFERENCES scenarios(id) ON DELETE CASCADE"),
                Map.entry("fk_examples_scenario_id",
                        "FOREIGN KEY (scenario_id) REFERENCES scenarios(id) ON DELETE CASCADE"),
                Map.entry("fk_example_rows_examples_id",
                        "FOREIGN KEY (examples_id) REFERENCES examples(id) ON DELETE CASCADE"),
                Map.entry("fk_tag_assignments_tag_id", "FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE RESTRICT"),
                Map.entry("fk_tag_assignments_feature_id",
                        "FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE"),
                Map.entry("uk_feature_versions_feature_id_version", "UNIQUE (feature_id, version)"),
                Map.entry("uk_tag_assignments_tag_id_target_type_target_id",
                        "UNIQUE (tag_id, target_type, target_id)")));
    }

    @Test
    @DisplayName("ERD-D12: every enum column is varchar(32) with a CHECK listing exactly the ERD's values")
    void enumColumnsHaveChecksWithTheErdValues() {
        Map<String, String> checks = constraints("c");

        assertThat(checks).containsOnlyKeys("ck_scenarios_kind_values", "ck_steps_keyword_values",
                "ck_steps_argument_type_values", "ck_tag_assignments_target_type_values");
        assertThat(checks.get("ck_scenarios_kind_values")).contains("kind");
        assertThat(values(checks.get("ck_scenarios_kind_values")))
                .containsExactly("BACKGROUND", "SCENARIO", "SCENARIO_OUTLINE");
        assertThat(checks.get("ck_steps_keyword_values")).contains("keyword");
        assertThat(values(checks.get("ck_steps_keyword_values")))
                .containsExactly("GIVEN", "WHEN", "THEN", "AND", "BUT", "ASTERISK");
        assertThat(checks.get("ck_steps_argument_type_values")).contains("argument_type");
        assertThat(values(checks.get("ck_steps_argument_type_values")))
                .containsExactly("NONE", "DATA_TABLE", "DOC_STRING");
        assertThat(checks.get("ck_tag_assignments_target_type_values")).contains("target_type");
        assertThat(values(checks.get("ck_tag_assignments_target_type_values")))
                .containsExactly("FEATURE", "RULE", "SCENARIO", "EXAMPLES");
    }

    @Test
    @DisplayName("D10, D14: the indexes, including the md5-hashed ICU path index (partial) and the md5 tag index")
    void theIndexesAreThoseOfTheErd() {
        Map<String, String> indexes = jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'").stream()
                .collect(Collectors.toMap(row -> (String) row.get("indexname"), row -> (String) row.get("indexdef")));

        assertThat(indexes).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("pk_features", "CREATE UNIQUE INDEX pk_features ON public.features USING btree (id)"),
                Map.entry("pk_feature_versions",
                        "CREATE UNIQUE INDEX pk_feature_versions ON public.feature_versions USING btree (id)"),
                Map.entry("pk_rules", "CREATE UNIQUE INDEX pk_rules ON public.rules USING btree (id)"),
                Map.entry("pk_scenarios", "CREATE UNIQUE INDEX pk_scenarios ON public.scenarios USING btree (id)"),
                Map.entry("pk_steps", "CREATE UNIQUE INDEX pk_steps ON public.steps USING btree (id)"),
                Map.entry("pk_examples", "CREATE UNIQUE INDEX pk_examples ON public.examples USING btree (id)"),
                Map.entry("pk_example_rows",
                        "CREATE UNIQUE INDEX pk_example_rows ON public.example_rows USING btree (id)"),
                Map.entry("pk_tags", "CREATE UNIQUE INDEX pk_tags ON public.tags USING btree (id)"),
                Map.entry("pk_tag_assignments",
                        "CREATE UNIQUE INDEX pk_tag_assignments ON public.tag_assignments USING btree (id)"),
                Map.entry("uk_features_project_id_path_lower",
                        "CREATE UNIQUE INDEX uk_features_project_id_path_lower ON public.features USING btree "
                                + "(project_id, md5(lower(((path)::text COLLATE \"und-x-icu\")))) "
                                + "WHERE (archived_at IS NULL)"),
                Map.entry("ix_features_project_id",
                        "CREATE INDEX ix_features_project_id ON public.features USING btree (project_id)"),
                Map.entry("uk_feature_versions_feature_id_version",
                        "CREATE UNIQUE INDEX uk_feature_versions_feature_id_version ON public.feature_versions "
                                + "USING btree (feature_id, version)"),
                Map.entry("ix_rules_feature_id",
                        "CREATE INDEX ix_rules_feature_id ON public.rules USING btree (feature_id)"),
                Map.entry("ix_scenarios_feature_id",
                        "CREATE INDEX ix_scenarios_feature_id ON public.scenarios USING btree (feature_id)"),
                Map.entry("ix_scenarios_rule_id",
                        "CREATE INDEX ix_scenarios_rule_id ON public.scenarios USING btree (rule_id)"),
                Map.entry("ix_steps_scenario_id",
                        "CREATE INDEX ix_steps_scenario_id ON public.steps USING btree (scenario_id)"),
                Map.entry("ix_examples_scenario_id",
                        "CREATE INDEX ix_examples_scenario_id ON public.examples USING btree (scenario_id)"),
                Map.entry("ix_example_rows_examples_id",
                        "CREATE INDEX ix_example_rows_examples_id ON public.example_rows USING btree (examples_id)"),
                Map.entry("uk_tags_project_id_name",
                        "CREATE UNIQUE INDEX uk_tags_project_id_name ON public.tags USING btree "
                                + "(project_id, md5(name))"),
                Map.entry("uk_tag_assignments_tag_id_target_type_target_id",
                        "CREATE UNIQUE INDEX uk_tag_assignments_tag_id_target_type_target_id ON public.tag_assignments "
                                + "USING btree (tag_id, target_type, target_id)"),
                Map.entry("ix_tag_assignments_target_type_target_id",
                        "CREATE INDEX ix_tag_assignments_target_type_target_id ON public.tag_assignments "
                                + "USING btree (target_type, target_id)"),
                Map.entry("ix_tag_assignments_feature_id_tag_id",
                        "CREATE INDEX ix_tag_assignments_feature_id_tag_id ON public.tag_assignments "
                                + "USING btree (feature_id, tag_id)")));
    }

    @Test
    @DisplayName("Item 10: every foreign-key column leads some index")
    void everyForeignKeyColumnLeadsAnIndex() {
        List<String> unindexed = jdbc.queryForList("SELECT c.conname FROM pg_constraint c "
                        + "WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace AND NOT EXISTS ("
                        + "SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])",
                String.class);

        assertThat(unindexed).isEmpty();
    }

    /**
     * Constraints of the given types ({@code pg_constraint.contype}), by name, as PostgreSQL prints them.
     */
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
