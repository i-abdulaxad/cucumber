package com.example.authservice.repository;

import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-2 (phase 4, Batch C): the database rejects data that breaks auth-service's rules. Every statement is direct SQL
 * in its own transaction, rolled back after the check; the expected SQLStates are PostgreSQL's (23502 not-null, 23505
 * unique, 23514 check, 23503 foreign key). Background: the Projects fixture, and Ben's pending invitation of Fay to
 * Acme.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class DatabaseRulesTest {

    private static final String NOT_NULL = "23502";
    private static final String CHECK = "23514";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private Projects projects;
    private Map<String, Row> rows;

    @BeforeEach
    void seedProjectsAndInviteFay() throws Exception {
        projects = fixture.seed();
        ProjectCalls calls = new ProjectCalls(mockMvc);
        MvcResult invited = calls.as("ben", post("/api/orgs/" + projects.acme().orgId() + "/invitations"),
                "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        UUID fayInvitation = UUID.fromString(JsonPath.read(invited.getResponse().getContentAsString(), "$.id"));
        UUID acme = projects.acme().orgId();
        rows = Map.of(
                "Ana's row", new Row("users", "id = ?", projects.acme().ana().getId()),
                "Acme's row", new Row("organizations", "id = ?", acme),
                "Cai's Acme membership", new Row("org_memberships", "user_id = ? AND org_id = ?",
                        projects.acme().cai().getId(), acme),
                "Checkout's row", new Row("projects", "id = ?", projects.checkout()),
                "Hal's Checkout membership", new Row("project_memberships", "user_id = ? AND project_id = ?",
                        projects.hal().getId(), projects.checkout()),
                "Ben's pending invitation of Fay to Acme", new Row("invitations", "id = ?", fayInvitation));
    }

    @ParameterizedTest(name = "AC-2.1 {0} = NULL on {1}")
    @CsvSource(delimiter = '|', value = {
            "full_name  | Ana's row", "status | Ana's row", "created_at | Ana's row", "updated_at | Ana's row",
            "name       | Acme's row", "plan | Acme's row", "created_at | Acme's row",
            "role       | Cai's Acme membership", "created_at | Cai's Acme membership",
            "name       | Checkout's row", "created_at | Checkout's row",
            "role       | Hal's Checkout membership", "created_at | Hal's Checkout membership",
            "email      | Ben's pending invitation of Fay to Acme", "role | Ben's pending invitation of Fay to Acme",
            "token      | Ben's pending invitation of Fay to Acme", "status | Ben's pending invitation of Fay to Acme",
            "expires_at | Ben's pending invitation of Fay to Acme",
            "created_at | Ben's pending invitation of Fay to Acme"})
    @Tag("AC-2.1")
    void requiredColumnsRejectAMissingValue(String column, String rowName) throws Exception {
        Row row = rows.get(rowName);

        assertThat(sqlState("UPDATE " + row.table() + " SET " + column + " = NULL WHERE " + row.where(), row.ids()))
                .as(row.table() + "." + column).isEqualTo(NOT_NULL);
    }

    @ParameterizedTest(name = "AC-2.5 seat_limit {0}")
    @CsvSource(delimiter = '|', value = {"0 | 23514", "-3 | 23514", "NULL | "})
    @Tag("AC-2.5")
    void aSeatLimitIsPositiveOrUnlimited(String value, String state) throws Exception {
        assertThat(sqlState("UPDATE organizations SET seat_limit = " + value + " WHERE id = ?",
                projects.acme().orgId())).isEqualTo(state == null ? null : CHECK);
    }

    @ParameterizedTest(name = "AC-2.4 {0} = {1} on {2}")
    @CsvSource(delimiter = '|', value = {
            "status | DELETED | Ana's row", "plan | GOLD | Acme's row",
            "role   | TESTER  | Cai's Acme membership", "role | admin | Cai's Acme membership",
            "role   | OWNER   | Hal's Checkout membership",
            "role   | TESTER  | Ben's pending invitation of Fay to Acme",
            "status | LOST    | Ben's pending invitation of Fay to Acme"})
    @Tag("AC-2.4")
    void enumColumnsAcceptOnlyTheDocumentedValues(String column, String value, String rowName) throws Exception {
        Row row = rows.get(rowName);
        Object[] parameters = new Object[row.ids().length + 1];
        parameters[0] = value;
        System.arraycopy(row.ids(), 0, parameters, 1, row.ids().length);

        assertThat(sqlState("UPDATE " + row.table() + " SET " + column + " = ? WHERE " + row.where(), parameters))
                .as(row.table() + "." + column).isEqualTo(CHECK);
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("E71: every enum column is varchar(32) with its named value check")
    void everyEnumColumnIsAShortVarcharWithANamedCheck() {
        List<String> columns = jdbc.queryForList("""
                SELECT c.table_name || '.' || c.column_name || ' ' || c.data_type || '(' || c.character_maximum_length
                       || ') ' || coalesce(k.conname, 'no check')
                FROM information_schema.columns c
                LEFT JOIN pg_constraint k ON k.conname = 'ck_' || c.table_name || '_' || c.column_name || '_values'
                WHERE c.table_schema = 'public'
                  AND (c.table_name, c.column_name) IN (('users', 'status'), ('organizations', 'plan'),
                       ('org_memberships', 'role'), ('project_memberships', 'role'), ('invitations', 'role'),
                       ('invitations', 'status'))
                ORDER BY 1""", String.class);

        assertThat(columns).containsExactly(
                "invitations.role character varying(32) ck_invitations_role_values",
                "invitations.status character varying(32) ck_invitations_status_values",
                "org_memberships.role character varying(32) ck_org_memberships_role_values",
                "organizations.plan character varying(32) ck_organizations_plan_values",
                "project_memberships.role character varying(32) ck_project_memberships_role_values",
                "users.status character varying(32) ck_users_status_values");
    }

    @ParameterizedTest(name = "AC-2.2 {0}")
    @CsvSource(delimiter = '|', value = {
            "sets Ana's email to Ana@example.com                  | 23514",
            "inserts a user with email ANA@EXAMPLE.COM            | 23514",
            "inserts a user with email ana@example.com            | 23505",
            "sets the email of Ben's invitation to Fay@Example.com | 23514"})
    @Tag("AC-2.2")
    void storedEmailsAreLowerCaseSoUniquenessIgnoresLetterCase(String statement, String state) throws Exception {
        String sql = "INSERT INTO users (id, email, password_hash, full_name, status, mfa_enabled, created_at, "
                + "updated_at) VALUES (gen_random_uuid(), ?, 'hash', 'Ana Two', 'ACTIVE', false, now(), now())";
        String actual = switch (statement) {
            case "sets Ana's email to Ana@example.com" -> sqlState("UPDATE users SET email = 'Ana@example.com' "
                    + "WHERE id = ?", projects.acme().ana().getId());
            case "inserts a user with email ANA@EXAMPLE.COM" -> sqlState(sql, "ANA@EXAMPLE.COM");
            case "inserts a user with email ana@example.com" -> sqlState(sql, "ana@example.com");
            case "sets the email of Ben's invitation to Fay@Example.com" -> sqlState(
                    "UPDATE invitations SET email = 'Fay@Example.com' WHERE id = ?",
                    rows.get("Ben's pending invitation of Fay to Acme").ids());
            default -> throw new IllegalArgumentException(statement);
        };

        assertThat(actual).isEqualTo(state);
    }

    @ParameterizedTest(name = "AC-2.3 {0}: {1} in {2}")
    @CsvSource(delimiter = '|', value = {
            "nothing else             | CHECKOUT | Acme       | 23505",
            "nothing else             | checkout | Acme       | 23505",
            "Ana has archived Billing | billing  | Acme       | 23505",
            "nothing else             | Checkout | Initech QA | "})
    @Tag("AC-2.3")
    void projectNamesAreUniquePerOrganizationIgnoringLetterCase(String setup, String name, String organization,
                                                                String state) throws Exception {
        if (setup.equals("Ana has archived Billing")) {
            fixture.archive(projects.billing());
        }
        UUID orgId = organization.equals("Acme") ? projects.acme().orgId() : projects.initech();

        assertThat(insertProject(orgId, name)).isEqualTo(state);
    }

    @Test
    @Tag("AC-2.3")
    @DisplayName("E72 (user decision 2026-10-15): İzmir and izmir can coexist, as under Java's Locale.ROOT")
    void izmirAndIzmirCanCoexist() throws Exception {
        UUID acme = projects.acme().orgId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertThat(insertProject(connection, acme, "\u0130zmir")).isNull();
                assertThat(insertProject(connection, acme, "izmir")).as("izmir next to \u0130zmir").isNull();
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    @Test
    @DisplayName("E72: lower() with the ICU root collation lower-cases like Java's Locale.ROOT (full case mapping)")
    void icuLowerCasingMatchesJava() {
        String icu = jdbc.queryForObject("SELECT lower('\u0130zmir' COLLATE \"und-x-icu\")", String.class);

        assertThat(icu).isEqualTo("\u0130zmir".toLowerCase(Locale.ROOT)).isEqualTo("i\u0307zmir");
    }

    @ParameterizedTest(name = "AC-2.6 deleting {0} is rejected")
    @CsvSource(delimiter = '|', value = {
            "Acme's organization row | organizations", "Ana's user row | users",
            "Checkout's project row  | projects", "Ivy's user row | users"})
    @Tag("AC-2.6")
    void aRowThatOthersDependOnCantBeDeleted(String rowName, String table) throws Exception {
        UUID id = switch (rowName) {
            case "Acme's organization row" -> projects.acme().orgId();
            case "Ana's user row" -> projects.acme().ana().getId();
            case "Checkout's project row" -> projects.checkout();
            case "Ivy's user row" -> projects.ivy().getId();
            default -> throw new IllegalArgumentException(rowName);
        };
        long rowsBefore = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        long dependentsBefore = dependents();

        assertThat(sqlState("DELETE FROM " + table + " WHERE id = ?", id)).isEqualTo("23503");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isEqualTo(rowsBefore);
        assertThat(dependents()).isEqualTo(dependentsBefore);
    }

    @Test
    @Tag("AC-2.7")
    void everyForeignKeyIsIndexedAndRestrictsDeletes() {
        List<String> foreignKeys = jdbc.queryForList("""
                SELECT c.conname || ' ' || c.confdeltype::text || ' '
                       || CASE WHEN EXISTS (SELECT 1 FROM pg_index i
                                            WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])
                               THEN 'indexed' ELSE 'not indexed' END
                FROM pg_constraint c
                JOIN pg_namespace s ON s.oid = c.connamespace
                WHERE s.nspname = 'public' AND c.contype = 'f'
                ORDER BY 1""", String.class);

        assertThat(foreignKeys).containsExactly(
                "fk_invitations_invited_by r indexed", "fk_invitations_org_id r indexed",
                "fk_org_memberships_org_id r indexed", "fk_org_memberships_user_id r indexed",
                "fk_project_memberships_project_id r indexed", "fk_project_memberships_user_id r indexed",
                "fk_projects_org_id r indexed");
    }

    /**
     * All rows that depend on users, organizations or projects.
     */
    private long dependents() {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM org_memberships) + (SELECT count(*) FROM projects)"
                + " + (SELECT count(*) FROM project_memberships) + (SELECT count(*) FROM invitations)", Long.class);
    }

    private String insertProject(UUID orgId, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                return insertProject(connection, orgId, name);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Inserts a project in the connection's open transaction; returns the SQLState it failed with, or null.
     */
    private static String insertProject(Connection connection, UUID orgId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO projects (id, org_id, name, created_at) VALUES (gen_random_uuid(), ?, ?, now())")) {
            statement.setObject(1, orgId);
            statement.setString(2, name);
            statement.executeUpdate();
            return null;
        } catch (SQLException rejected) {
            return rejected.getSQLState();
        }
    }

    /**
     * Runs one statement in its own transaction and rolls it back. Returns the SQLState it failed with, or
     * {@code null} if it succeeded.
     */
    private String sqlState(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                statement.executeUpdate();
                return null;
            } catch (SQLException rejected) {
                return rejected.getSQLState();
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * A fixture row: its table, a WHERE clause with placeholders, and the parameter values.
     */
    private record Row(String table, String where, Object... ids) {
    }
}
