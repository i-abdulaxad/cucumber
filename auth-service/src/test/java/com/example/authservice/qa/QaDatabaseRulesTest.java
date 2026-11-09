package com.example.authservice.qa;

import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.example.authservice.util.EmailNormalizer;
import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, phase 4 US-2 and D8/D10 (adversarial): each database rule checked on its own, beyond the spec's example rows.
 * Every direct-SQL statement runs in its own transaction on the shared test database and is rolled back. Background:
 * the Projects fixture.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class QaDatabaseRulesTest {

    private static final String EMOJI = "\uD83D\uDE00";
    /**
     * U+10400 DESERET CAPITAL LETTER LONG I: a supplementary letter (two UTF-16 units, one code point).
     */
    private static final String DESERET = "\uD801\uDC00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private Environment environment;

    private Projects projects;
    private ProjectCalls calls;

    @BeforeEach
    void seedProjects() {
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
    }

    @ParameterizedTest(name = "qa AC-2.6 deleting the parent of only a {0} row is rejected by {0}")
    @ValueSource(strings = {"fk_org_memberships_user_id", "fk_org_memberships_org_id", "fk_projects_org_id",
            "fk_project_memberships_user_id", "fk_project_memberships_project_id", "fk_invitations_org_id",
            "fk_invitations_invited_by"})
    @Tag("AC-2.6")
    void everyForeignKeyRestrictsDeletesOnItsOwn(String foreignKey) throws Exception {
        UUID ana = projects.acme().ana().getId();
        UUID acme = projects.acme().orgId();
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();

        SQLException rejected = rolledBack(connection -> {
            switch (foreignKey) {
                case "fk_org_memberships_user_id" -> {
                    insertUser(connection, parent, "qa-parent@example.com");
                    insertOrgMembership(connection, child, parent, acme);
                    delete(connection, "users", parent);
                }
                case "fk_org_memberships_org_id" -> {
                    insertOrganization(connection, parent);
                    insertOrgMembership(connection, child, ana, parent);
                    delete(connection, "organizations", parent);
                }
                case "fk_projects_org_id" -> {
                    insertOrganization(connection, parent);
                    insertProject(connection, child, parent, "Parent QA");
                    delete(connection, "organizations", parent);
                }
                case "fk_project_memberships_user_id" -> {
                    insertUser(connection, parent, "qa-parent@example.com");
                    insertProjectMembership(connection, child, parent, projects.checkout());
                    delete(connection, "users", parent);
                }
                case "fk_project_memberships_project_id" -> {
                    insertProject(connection, parent, acme, "Parent QA");
                    insertProjectMembership(connection, child, ana, parent);
                    delete(connection, "projects", parent);
                }
                case "fk_invitations_org_id" -> {
                    insertOrganization(connection, parent);
                    insertInvitation(connection, child, parent, ana);
                    delete(connection, "organizations", parent);
                }
                case "fk_invitations_invited_by" -> {
                    insertUser(connection, parent, "qa-parent@example.com");
                    insertInvitation(connection, child, acme, parent);
                    delete(connection, "users", parent);
                }
                default -> throw new IllegalArgumentException(foreignKey);
            }
        });

        assertThat((Object) rejected).as("the delete is rejected").isNotNull();
        assertThat(rejected.getSQLState()).isEqualTo("23503");
        assertThat(rejected.getMessage()).contains("\"" + foreignKey + "\"");
    }

    @Test
    @Tag("AC-2.7")
    @DisplayName("qa AC-2.7: every foreign key's columns lead a valid, non-partial index; the FK indexes are the ERD's")
    void everyForeignKeyLeadsAValidFullIndex() {
        List<String> unindexed = jdbc.queryForList("""
                SELECT c.conname FROM pg_constraint c
                WHERE c.connamespace = 'public'::regnamespace AND c.contype = 'f'
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indpred IS NULL
                        AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] = c.conkey)""", String.class);
        List<String> foreignKeyIndexes = jdbc.queryForList("""
                SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i
                JOIN pg_class x ON x.oid = i.indexrelid
                WHERE x.relnamespace = 'public'::regnamespace AND x.relname LIKE 'ix\\_%'
                ORDER BY 1""", String.class);

        assertThat(unindexed).isEmpty();
        assertThat(foreignKeyIndexes).containsExactly(
                "CREATE INDEX ix_invitations_invited_by ON public.invitations USING btree (invited_by)",
                "CREATE INDEX ix_invitations_org_id_email ON public.invitations USING btree (org_id, email)",
                "CREATE INDEX ix_org_memberships_org_id ON public.org_memberships USING btree (org_id)",
                "CREATE INDEX ix_project_memberships_project_id ON public.project_memberships USING btree (project_id)");
    }

    @Test
    @Tag("AC-2.7")
    @DisplayName("qa D4: every constraint has its readable D4 name, and every foreign key is RESTRICT on delete")
    void everyConstraintHasItsReadableName() {
        List<String> constraints = jdbc.queryForList("""
                SELECT c.conname || ' ' || c.contype::text
                       || CASE WHEN c.contype = 'f' THEN ' delete=' || c.confdeltype::text ELSE '' END
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE c.connamespace = 'public'::regnamespace AND t.relname <> 'flyway_schema_history'
                  AND c.contype <> 'n'
                ORDER BY 1""", String.class);

        assertThat(constraints).containsExactly(
                "ck_invitations_email_lower_case c", "ck_invitations_role_values c", "ck_invitations_status_values c",
                "ck_org_memberships_role_values c", "ck_organizations_plan_values c",
                "ck_organizations_seat_limit_positive c", "ck_project_memberships_role_values c",
                "ck_users_email_lower_case c", "ck_users_status_values c",
                "fk_invitations_invited_by f delete=r", "fk_invitations_org_id f delete=r",
                "fk_org_memberships_org_id f delete=r", "fk_org_memberships_user_id f delete=r",
                "fk_project_memberships_project_id f delete=r", "fk_project_memberships_user_id f delete=r",
                "fk_projects_org_id f delete=r",
                "pk_invitations p", "pk_org_memberships p", "pk_organizations p", "pk_project_memberships p",
                "pk_projects p", "pk_users p",
                "uk_invitations_token u", "uk_org_memberships_user_id_org_id u",
                "uk_project_memberships_user_id_project_id u", "uk_users_email u");
    }

    @ParameterizedTest(name = "qa D8 item 2: {0} holds {1} code points, not {2}")
    @CsvSource(delimiter = '|', value = {
            "users.full_name    | 100 | 101", "organizations.name | 100 | 101", "projects.name | 100 | 101",
            "users.email        | 254 | 255", "invitations.email  | 254 | 255"})
    @Tag("AC-2.1")
    void lengthsAreCountedInCodePoints(String column, int fits, int tooLong) throws Exception {
        String[] parts = column.split("\\.");
        boolean email = parts[1].equals("email");
        String unit = email ? "a" : column.startsWith("users") ? EMOJI : DESERET;
        UUID id = switch (parts[0]) {
            case "users" -> projects.acme().ana().getId();
            case "organizations" -> projects.acme().orgId();
            case "projects" -> projects.checkout();
            default -> fayInvitation();
        };

        assertThat(update(parts[0], parts[1], value(unit, fits, email), id)).as(fits + " fit").isNull();
        assertThat(update(parts[0], parts[1], value(unit, tooLong, email), id)).as(tooLong + " don't fit")
                .isEqualTo("22001");
    }

    @ParameterizedTest(name = "qa AC-2.4 {0} = \"{1}\" is rejected")
    @CsvSource(delimiter = '|', value = {"role | 'owner'", "role | 'OWNER '", "role | ''", "role | ' OWNER'"})
    @Tag("AC-2.4")
    void enumChecksAreExactAndCaseSensitive(String column, String value) throws Exception {
        SQLException rejected = rolledBack(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE org_memberships SET " + column + " = ? WHERE user_id = ? AND org_id = ?")) {
                statement.setString(1, value);
                statement.setObject(2, projects.acme().cai().getId());
                statement.setObject(3, projects.acme().orgId());
                statement.executeUpdate();
            }
        });
        assertThat((Object) rejected).isNotNull();
        assertThat(rejected.getSQLState()).isEqualTo("23514");
    }

    @ParameterizedTest(name = "qa AC-2.5 seat_limit {0} is accepted")
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    @Tag("AC-2.5")
    void positiveSeatLimitsAreAccepted(int limit) throws Exception {
        assertThat((Object) rolledBack(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE organizations SET seat_limit = ? WHERE id = ?")) {
                statement.setInt(1, limit);
                statement.setObject(2, projects.acme().orgId());
                statement.executeUpdate();
            }
        })).isNull();
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("qa D10: PostgreSQL's error DETAIL (stored values) never reaches an exception message")
    void theServerErrorDetailNeverReachesAMessage() throws Exception {
        SQLException duplicate = rolledBack(connection -> insertUser(connection, UUID.randomUUID(), "ana@example.com"));
        SQLException upperCase = rolledBack(connection ->
                insertUser(connection, UUID.randomUUID(), "QA-MARKER-61@EXAMPLE.COM"));

        assertThat(duplicate.getSQLState()).isEqualTo("23505");
        assertThat(upperCase.getSQLState()).isEqualTo("23514");
        for (SQLException rejected : List.of(duplicate, upperCase)) {
            assertThat(rejected.getClass().getName()).isEqualTo("org.postgresql.util.PSQLException");
            assertThat(rejected.getMessage().toLowerCase(Locale.ROOT))
                    .doesNotContain("ana@example.com", "qa-marker-61", "key (", "failing row", "ana lima");
        }
    }

    @Test
    @Tag("AC-5.1")
    @DisplayName("qa US-5 rationale: PostgreSQL refuses U+0000, and a lone surrogate would be stored changed")
    void postgresCantStoreNulAndChangesALoneSurrogate() throws Exception {
        SQLException nul = rolledBack(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE organizations SET name = ? WHERE id = ?")) {
                statement.setString(1, "Org\u0000QA");
                statement.setObject(2, projects.acme().orgId());
                statement.executeUpdate();
            }
        });
        assertThat((Object) nul).isNotNull();
        assertThat(nul.getSQLState()).isEqualTo("22021");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT ?::text")) {
                statement.setString(1, "Org\uD800QA");
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getString(1)).isNotEqualTo("Org\uD800QA").isEqualTo("Org?QA");
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    @ParameterizedTest(name = "qa AC-2.2 the API stores {0} lower case and the database agrees")
    @ValueSource(strings = {"\u0130nci@example.com", "\u1E9Etra\u00DFe@example.com",
            "\u03A3\u039F\u03A6\u0399\u0391@example.com", "\u212Aelvin@example.com", "\u01C4emal@example.com",
            "\u0414\u041C\u0418\u0422\u0420\u0418\u0419@example.com", "\u0531\u0550\u0531\u0544@example.com",
            "Ana.Two+QA@Example.COM"})
    @Tag("AC-2.2")
    void unicodeEmailsAreStoredInALowerCaseTheDatabaseAccepts(String email) throws Exception {
        // When
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123!\",\"fullName\":\"Uni QA\"}"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        String stored = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
                UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id")));
        assertThat(stored).isEqualTo(EmailNormalizer.normalize(email));
        assertThat(jdbc.queryForObject("SELECT lower(?) = ?", Boolean.class, stored, stored)).isTrue();
    }

    @ParameterizedTest(name = "qa AC-2.3 \"{0}\" then \"{1}\" in Acme: {2}")
    @CsvSource(delimiter = '|', value = {
            "\u0130zmir | i\u0307zmir | 409", "\u1E9E | \u00DF | 409", "\u03A3\u0391\u03A3 | \u03C3\u03B1\u03C2 | 409",
            "\u212Ax | kx | 409", "\u01C5 | \u01C6 | 409", "\u0130zmir | izmir | 201", "I | \u0131 | 201",
            "\u00DF | ss | 201", "\uD801\uDC00 | \uD801\uDC28 | 409"})
    @Tag("AC-2.3")
    void theProjectNameIndexAgreesWithTheApplicationsRule(String first, String second, int status) throws Exception {
        // Given
        String acmeProjects = projects(projects.acme().orgId());
        assertThat(calls.as("ana", post(acmeProjects), "{\"name\":\"" + first + "\"}").getResponse().getStatus())
                .isEqualTo(201);

        // When
        MvcResult result = calls.as("ana", post(acmeProjects), "{\"name\":\"" + second + "\"}");

        // Then: the application's rule answers; the index never has to (that would be a generic CONFLICT)
        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        } else {
            assertProblem(result, 409, "PROJECT_NAME_TAKEN", acmeProjects);
        }
        assertThat(jdbc.queryForObject("SELECT lower(? COLLATE \"und-x-icu\") = lower(? COLLATE \"und-x-icu\")",
                Boolean.class, first, second)).isEqualTo(status == 409);
        assertThat(first.toLowerCase(Locale.ROOT).equals(second.toLowerCase(Locale.ROOT))).isEqualTo(status == 409);
    }

    @Test
    @Tag("AC-1.4")
    @DisplayName("qa D4: Flyway runs with Boot's defaults (validate on migrate, clean disabled, no baseline) and "
            + "Hibernate only validates; bound values are never logged")
    void flywayAndHibernateRunWithTheD4Settings() {
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                .containsExactly("classpath:db/migration");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind").isDebugEnabled()).isFalse();
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Sql {
        void run(Connection connection) throws SQLException;
    }

    /**
     * Runs the statements in one transaction and rolls it back; returns the exception they failed with, or null.
     */
    private SQLException rolledBack(Sql sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                sql.run(connection);
                return null;
            } catch (SQLException rejected) {
                return rejected;
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    private String update(String table, String column, String value, UUID id) throws SQLException {
        SQLException rejected = rolledBack(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + table + " SET " + column + " = ? WHERE id = ?")) {
                statement.setString(1, value);
                statement.setObject(2, id);
                statement.executeUpdate();
            }
        });
        return rejected == null ? null : rejected.getSQLState();
    }

    private static String value(String unit, int codePoints, boolean email) {
        if (!email) {
            return unit.repeat(codePoints);
        }
        String domain = "@example.com";
        return unit.repeat(codePoints - domain.length()) + domain;
    }

    private UUID fayInvitation() throws Exception {
        MvcResult invited = calls.as("ben", post("/api/orgs/" + projects.acme().orgId() + "/invitations"),
                "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(invited.getResponse().getContentAsString(), "$.id"));
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static void insertUser(Connection connection, UUID id, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO users (id, email, password_hash,"
                + " full_name, status, mfa_enabled, created_at, updated_at) VALUES (?, ?, 'hash', 'Parent QA',"
                + " 'ACTIVE', false, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, email);
            statement.setTimestamp(3, now());
            statement.setTimestamp(4, now());
            statement.executeUpdate();
        }
    }

    private static void insertOrganization(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO organizations (id, name, plan, seat_limit, created_at) VALUES (?, 'Parent QA', 'FREE', 5, ?)")) {
            statement.setObject(1, id);
            statement.setTimestamp(2, now());
            statement.executeUpdate();
        }
    }

    private static void insertOrgMembership(Connection connection, UUID id, UUID user, UUID org) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO org_memberships (id, user_id, org_id, role, created_at) VALUES (?, ?, ?, 'MEMBER', ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, user);
            statement.setObject(3, org);
            statement.setTimestamp(4, now());
            statement.executeUpdate();
        }
    }

    private static void insertProject(Connection connection, UUID id, UUID org, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO projects (id, org_id, name, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, org);
            statement.setString(3, name);
            statement.setTimestamp(4, now());
            statement.executeUpdate();
        }
    }

    private static void insertProjectMembership(Connection connection, UUID id, UUID user, UUID project)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO project_memberships (id, user_id,"
                + " project_id, role, created_at) VALUES (?, ?, ?, 'READER', ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, user);
            statement.setObject(3, project);
            statement.setTimestamp(4, now());
            statement.executeUpdate();
        }
    }

    private static void insertInvitation(Connection connection, UUID id, UUID org, UUID invitedBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO invitations (id, email, org_id,"
                + " role, invited_by, token, status, expires_at, created_at)"
                + " VALUES (?, 'child-qa@example.com', ?, 'MEMBER', ?, ?, 'PENDING', ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, org);
            statement.setObject(3, invitedBy);
            statement.setString(4, "tok-" + id);
            statement.setTimestamp(5, now());
            statement.setTimestamp(6, now());
            statement.executeUpdate();
        }
    }

    private static void delete(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }
}
