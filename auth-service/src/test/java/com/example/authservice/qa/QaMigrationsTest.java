package com.example.authservice.qa;

import com.example.authservice.AuthServiceApplication;
import com.example.authservice.support.ScratchDatabase;
import com.example.authservice.support.TestJwt;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.example.authservice.support.TestDatabase.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * qa, phase 4 (US-1, D4, P17): adversarial checks of the Flyway migrations on scratch databases (never the shared test
 * database). They prove that the migration files follow the D4 rules, that V1 reproduces the pre-phase-4 as-built
 * schema, that every version applies on an empty database one by one (also on a database that doesn't use the
 * builtin C.UTF-8 locale), that Hibernate's own PostgreSQL DDL for today's entities has the same columns as the
 * migrated schema, and that start-up refuses the kinds of drift Flyway and {@code ddl-auto: validate} can see.
 */
class QaMigrationsTest {

    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__([a-z0-9_]+)\\.sql");

    @Test
    @Tag("AC-1.1")
    @DisplayName("qa D4/P17: migration files are V1..Vn without gaps, snake_case, versioned only, and deterministic")
    void migrationFilesFollowTheRules() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*");
        List<Integer> versions = new ArrayList<>();
        for (Resource file : files) {
            Matcher name = MIGRATION.matcher(String.valueOf(file.getFilename()));
            assertThat(name.matches()).as("file name " + file.getFilename()).isTrue();
            versions.add(Integer.parseInt(name.group(1)));
            String sql = file.getContentAsString(StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            assertThat(sql).as(file.getFilename() + " has no IF [NOT] EXISTS")
                    .doesNotContainPattern("IF\\s+(NOT\\s+)?EXISTS");
            assertThat(sql).as(file.getFilename() + " leaves transactions to Flyway")
                    .doesNotContainPattern("(?m)^\\s*(BEGIN|COMMIT|ROLLBACK)\\b");
            assertThat(sql).as(file.getFilename() + " uses no PostgreSQL ENUM type")
                    .doesNotContainPattern("CREATE\\s+TYPE");
        }
        versions.sort(Comparator.naturalOrder());
        assertThat(versions).as("contiguous versions from 1")
                .isEqualTo(IntStream.rangeClosed(1, versions.size()).boxed().toList());
        assertThat(versions).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("qa D4: every migration applies on an empty database one version at a time, and V1 is the "
            + "pre-phase-4 as-built schema")
    void everyMigrationAppliesOneByOneAndV1IsTheAsBuiltSchema() throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            int latest = latestVersion();
            for (int version = 1; version <= latest; version++) {
                MigrateResult result = flyway(scratch.url(), String.valueOf(version)).migrate();
                assertThat(result.success).as("V" + version).isTrue();
                assertThat(result.migrationsExecuted).as("V" + version + " runs alone").isEqualTo(1);
                if (version == 1) {
                    assertThat(columns(scratch.url())).as("V1 = the as-built ERD before phase 4, in PostgreSQL types")
                            .isEqualTo(V1_COLUMNS);
                    assertThat(checkValues(scratch.url())).isEqualTo(ENUM_VALUES);
                }
            }
            assertThat(flyway(scratch.url(), "latest").migrate().migrationsExecuted).as("nothing left").isZero();
            flyway(scratch.url(), "latest").validate();
            assertThat(checkValues(scratch.url())).as("V2..Vn keep the enum values").isEqualTo(ENUM_VALUES);
        }
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("qa D3: the migrations also apply on a database with another locale (libc en_US.utf8)")
    void theMigrationsApplyOnADatabaseWithAnotherLocale() throws Exception {
        String name = "qa_libc_" + UUID.randomUUID().toString().replace("-", "");
        execute(POSTGRES.getJdbcUrl(), "CREATE DATABASE " + name
                + " TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER libc LOCALE 'en_US.utf8'");
        try {
            String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
            assertThat(flyway(url, "latest").migrate().success).isTrue();
            assertThat(columns(url)).isEqualTo(columnsOfALatestScratchDatabase());
        } finally {
            execute(POSTGRES.getJdbcUrl(), "DROP DATABASE " + name + " WITH (FORCE)");
        }
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("qa D4/pattern 7: Hibernate's own DDL for today's entities has the migrated columns; only the enum "
            + "columns differ (varchar(255) in the entities, varchar(32) in V4)")
    void hibernatesDdlForTheEntitiesMatchesTheMigratedColumns() throws Exception {
        try (ScratchDatabase migrated = ScratchDatabase.create(); ScratchDatabase generated = ScratchDatabase.create()) {
            flyway(migrated.url(), "latest").migrate();
            start(generated, "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=create");

            Set<String> onlyMigrated = new TreeSet<>(columns(migrated.url()));
            onlyMigrated.removeAll(columns(generated.url()));
            Set<String> onlyGenerated = new TreeSet<>(columns(generated.url()));
            onlyGenerated.removeAll(columns(migrated.url()));

            List<String> enums = List.of("INVITATIONS.ROLE", "INVITATIONS.STATUS", "ORGANIZATIONS.PLAN",
                    "ORG_MEMBERSHIPS.ROLE", "PROJECT_MEMBERSHIPS.ROLE", "USERS.STATUS");
            assertThat(onlyMigrated).containsExactlyElementsOf(
                    enums.stream().map(column -> column + " CHARACTER VARYING(32) NOT NULL").toList());
            assertThat(onlyGenerated).containsExactlyElementsOf(
                    enums.stream().map(column -> column + " CHARACTER VARYING(255) NOT NULL").toList());
        }
    }

    @ParameterizedTest(name = "qa AC-1.3/1.4 drift: {0}")
    @CsvSource(delimiter = '|', value = {
            "a boolean column became text | ALTER TABLE users ALTER COLUMN mfa_enabled TYPE varchar(5)"
                    + " | mfa_enabled",
            "the table organizations was renamed | ALTER TABLE organizations RENAME TO orgs_qa | organizations",
            "the latest migration is recorded as failed | UPDATE flyway_schema_history SET success = false"
                    + " WHERE version = (SELECT max(version::int)::text FROM flyway_schema_history) | failed migration",
            "the checksum of V5 differs from its file | UPDATE flyway_schema_history SET checksum = checksum + 1"
                    + " WHERE version = '5' | version 5"})
    @Tag("AC-1.3")
    void startUpRefusesDriftThatFlywayOrValidateCanSee(String drift, String sql, String named) throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given: migrated with Flyway directly (FU-21: one application start fewer per row)
            flyway(scratch.url(), "latest").migrate();
            execute(scratch.url(), sql);
            List<String> history = history(scratch.url());
            Set<String> columns = columns(scratch.url());

            // When
            Throwable failure = catchThrowable(() -> start(scratch));

            // Then
            assertThat(failure).as(drift).isNotNull();
            List<String> messages = new ArrayList<>();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.add(String.valueOf(cause.getMessage()));
            }
            assertThat(messages).anySatisfy(message -> assertThat(message).containsIgnoringCase(named));
            assertThat(history(scratch.url())).isEqualTo(history);
            assertThat(columns(scratch.url())).isEqualTo(columns);
        }
    }

    // --- expected values ---------------------------------------------------------------------------------------

    /**
     * The phase-2 as-built ERD (qa's QaSchemaTest at HEAD), in PostgreSQL type names: enums are varchar(255).
     */
    private static final Set<String> V1_COLUMNS = new TreeSet<>(List.of(
            "USERS.ID UUID NOT NULL", "USERS.EMAIL CHARACTER VARYING(255) NOT NULL",
            "USERS.PASSWORD_HASH CHARACTER VARYING(255) NULL", "USERS.FULL_NAME CHARACTER VARYING(255) NULL",
            "USERS.STATUS CHARACTER VARYING(255) NULL", "USERS.MFA_ENABLED BOOLEAN NOT NULL",
            "USERS.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL", "USERS.UPDATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "ORGANIZATIONS.ID UUID NOT NULL", "ORGANIZATIONS.NAME CHARACTER VARYING(255) NULL",
            "ORGANIZATIONS.PLAN CHARACTER VARYING(255) NULL", "ORGANIZATIONS.SEAT_LIMIT INTEGER NULL",
            "ORGANIZATIONS.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "ORG_MEMBERSHIPS.ID UUID NOT NULL", "ORG_MEMBERSHIPS.USER_ID UUID NOT NULL",
            "ORG_MEMBERSHIPS.ORG_ID UUID NOT NULL", "ORG_MEMBERSHIPS.ROLE CHARACTER VARYING(255) NULL",
            "ORG_MEMBERSHIPS.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "PROJECTS.ID UUID NOT NULL", "PROJECTS.ORG_ID UUID NOT NULL", "PROJECTS.NAME CHARACTER VARYING(255) NULL",
            "PROJECTS.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "PROJECTS.ARCHIVED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "PROJECT_MEMBERSHIPS.ID UUID NOT NULL", "PROJECT_MEMBERSHIPS.USER_ID UUID NOT NULL",
            "PROJECT_MEMBERSHIPS.PROJECT_ID UUID NOT NULL", "PROJECT_MEMBERSHIPS.ROLE CHARACTER VARYING(255) NULL",
            "PROJECT_MEMBERSHIPS.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "INVITATIONS.ID UUID NOT NULL", "INVITATIONS.EMAIL CHARACTER VARYING(255) NULL",
            "INVITATIONS.ORG_ID UUID NOT NULL", "INVITATIONS.ROLE CHARACTER VARYING(255) NULL",
            "INVITATIONS.INVITED_BY UUID NOT NULL", "INVITATIONS.TOKEN CHARACTER VARYING(255) NULL",
            "INVITATIONS.STATUS CHARACTER VARYING(255) NULL",
            "INVITATIONS.EXPIRES_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "INVITATIONS.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL",
            "AUDIT_LOG.ID UUID NOT NULL", "AUDIT_LOG.ACTOR_ID UUID NOT NULL",
            "AUDIT_LOG.ACTION CHARACTER VARYING(255) NULL", "AUDIT_LOG.ORG_ID UUID NULL",
            "AUDIT_LOG.PROJECT_ID UUID NULL", "AUDIT_LOG.TARGET_USER_ID UUID NULL", "AUDIT_LOG.METADATA JSON NULL",
            "AUDIT_LOG.CREATED_AT TIMESTAMP(6) WITHOUT TIME ZONE NULL"));

    private static final Set<String> ENUM_VALUES = new TreeSet<>(List.of(
            "USERS.STATUS=ACTIVE", "USERS.STATUS=SUSPENDED", "USERS.STATUS=PENDING_VERIFICATION",
            "ORGANIZATIONS.PLAN=FREE", "ORGANIZATIONS.PLAN=TEAM", "ORGANIZATIONS.PLAN=ENTERPRISE",
            "ORG_MEMBERSHIPS.ROLE=OWNER", "ORG_MEMBERSHIPS.ROLE=ADMIN", "ORG_MEMBERSHIPS.ROLE=MEMBER",
            "ORG_MEMBERSHIPS.ROLE=READER",
            "PROJECT_MEMBERSHIPS.ROLE=ADMIN", "PROJECT_MEMBERSHIPS.ROLE=MEMBER", "PROJECT_MEMBERSHIPS.ROLE=TESTER",
            "PROJECT_MEMBERSHIPS.ROLE=READER",
            "INVITATIONS.STATUS=PENDING", "INVITATIONS.STATUS=ACCEPTED", "INVITATIONS.STATUS=REVOKED",
            "INVITATIONS.STATUS=EXPIRED",
            "INVITATIONS.ROLE=OWNER", "INVITATIONS.ROLE=ADMIN", "INVITATIONS.ROLE=MEMBER", "INVITATIONS.ROLE=READER"));

    // --- helpers -----------------------------------------------------------------------------------------------

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static int latestVersion() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
        return Arrays.stream(files)
                .map(file -> MIGRATION.matcher(String.valueOf(file.getFilename())))
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max().orElseThrow();
    }

    private static Set<String> columnsOfALatestScratchDatabase() throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            flyway(scratch.url(), "latest").migrate();
            return columns(scratch.url());
        }
    }

    private static void start(ScratchDatabase scratch, String... extra) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=",
                "--app.jwtSecret=" + TestJwt.SECRET, "--AUTH_DB_URL=" + scratch.url(),
                "--AUTH_DB_USERNAME=" + scratch.username(), "--AUTH_DB_PASSWORD=" + scratch.password()));
        arguments.addAll(List.of(extra));
        new SpringApplicationBuilder(AuthServiceApplication.class).run(arguments.toArray(String[]::new)).close();
    }

    private static Set<String> columns(String url) throws SQLException {
        return new TreeSet<>(query(url, """
                SELECT upper(table_name) || '.' || upper(column_name) || ' '
                       || CASE WHEN data_type = 'character varying'
                                   THEN 'CHARACTER VARYING(' || character_maximum_length || ')'
                               WHEN data_type LIKE 'timestamp%'
                                   THEN replace(upper(data_type), 'TIMESTAMP', 'TIMESTAMP(' || datetime_precision || ')')
                               ELSE upper(data_type) END
                       || CASE WHEN is_nullable = 'YES' THEN ' NULL' ELSE ' NOT NULL' END
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'"""));
    }

    private static Set<String> checkValues(String url) throws SQLException {
        return new TreeSet<>(query(url, """
                SELECT upper(t.relname) || '.' || upper(a.attname) || '=' || v.value[1]
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace s ON s.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                CROSS JOIN LATERAL regexp_matches(pg_get_constraintdef(c.oid), '''([^'']+)''', 'g') AS v(value)
                WHERE s.nspname = 'public' AND c.contype = 'c' AND cardinality(c.conkey) = 1
                  AND t.relname <> 'audit_log'"""));
    }

    private static List<String> history(String url) throws SQLException {
        return query(url, "SELECT version || ' ' || checksum || ' ' || success FROM flyway_schema_history "
                + "ORDER BY installed_rank");
    }

    private static List<String> query(String url, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
