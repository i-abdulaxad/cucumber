package org.scenarioservice.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T2, D10 and D14 (direct SQL, pattern item 17): the two hashed unique indexes. Paths are unique per project among
 * active features, compared by ICU root lower case ({@code lower(path COLLATE "und-x-icu")}); tags are unique per
 * project, exactly. Both indexes store the {@code md5} of the compared value, because valid paths and tags exceed
 * PostgreSQL's 2,704-byte B-tree entry limit (user decision 2026-10-18, option A). Each test runs in one transaction
 * on its own connection and rolls it back, so the shared test database keeps no rows (item 15).
 */
@ScenarioServiceIntegrationTest
class UniqueIndexTest {

    private static final int BTREE_ENTRY_LIMIT = 2704;
    private static final Pattern CONSTRAINT = Pattern.compile("violates unique constraint \"([a-z_]+)\"");

    @Autowired
    private DataSource dataSource;

    private Connection connection;
    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollBack() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }

    @Test
    @Tag("AC-1.17")
    @DisplayName("D10: İzmir collides with i̇zmir (ICU root lower case), not with izmir")
    void pathsAreComparedByIcuRootLowerCase() throws Exception {
        insertFeature(project, "features/İzmir.feature", null);

        assertThat(uniqueViolation(() -> insertFeature(project, "features/i̇zmir.feature", null)))
                .isEqualTo("uk_features_project_id_path_lower");
        insertFeature(project, "features/izmir.feature", null);
        assertThat(count("SELECT count(*) FROM features WHERE project_id = ?", project)).isEqualTo(2);
    }

    @Test
    @Tag("AC-1.17")
    @DisplayName("D10 FU-17: U+2C2F (Unicode 14) collides with U+2C5F in the index, although Java 17 doesn't map it")
    void aUnicodeFourteenCasePairCollides() throws Exception {
        insertFeature(project, "features/Ⱟ.feature", null);

        assertThat(uniqueViolation(() -> insertFeature(project, "features/ⱟ.feature", null)))
                .isEqualTo("uk_features_project_id_path_lower");
    }

    @Test
    @DisplayName("D10: paths are unique per project only, and an archived feature's path is free")
    void anArchivedPathIsFreeAndOtherProjectsAreIndependent() throws Exception {
        insertFeature(project, "features/checkout/payment.feature", Instant.parse("2026-10-18T10:00:00Z"));
        insertFeature(project, "Features/Checkout/PAYMENT.feature", null);
        insertFeature(UUID.randomUUID(), "features/checkout/payment.feature", null);

        assertThat(uniqueViolation(() -> insertFeature(project, "features/checkout/payment.feature", null)))
                .isEqualTo("uk_features_project_id_path_lower");
    }

    @Test
    @Tag("AC-1.17")
    @DisplayName("D14 B-tree: a 1,024-code-point path of four-byte Deseret capitals is stored; its small-letter copy "
            + "is rejected")
    void aMaximumLengthFourBytePathIsStoredAndItsOtherCaseCopyRejected() throws Exception {
        String capitals = "features/" + cycle(0x10400, 0x10426, 1007) + ".feature";
        String smalls = "features/" + cycle(0x10428, 0x1044E, 1007) + ".feature";
        assertThat(capitals.codePointCount(0, capitals.length())).isEqualTo(1024);
        assertThat(capitals.toLowerCase(Locale.ROOT)).isEqualTo(smalls);

        insertFeature(project, capitals, null);

        assertThat(octets("SELECT octet_length(lower(path COLLATE \"und-x-icu\")) FROM features WHERE project_id = ?"))
                .as("larger than a B-tree entry, so only the hash can be indexed").isGreaterThan(BTREE_ENTRY_LIMIT);
        assertThat(uniqueViolation(() -> insertFeature(project, smalls, null)))
                .isEqualTo("uk_features_project_id_path_lower");
    }

    @Test
    @DisplayName("D14 B-tree: a 1,024-code-point path of distinct emoji (they don't compress) is stored")
    void aMaximumLengthPathOfDistinctEmojiIsStored() throws Exception {
        String path = "features/" + cycle(0x1F300, 0x1F300 + 1007, 1007) + ".feature";
        assertThat(path.codePointCount(0, path.length())).isEqualTo(1024);

        insertFeature(project, path, null);

        assertThat(octets("SELECT octet_length(path) FROM features WHERE project_id = ?")).isEqualTo(9 + 4 * 1007 + 8);
    }

    @Test
    @DisplayName("D14 B-tree: a 1,024-character path of three-byte CJK characters is stored")
    void aMaximumLengthThreeBytePathIsStored() throws Exception {
        String path = "features/" + cycle(0x4E00, 0x4E00 + 1007, 1007) + ".feature";
        assertThat(path.length()).isEqualTo(1024);

        insertFeature(project, path, null);

        assertThat(octets("SELECT octet_length(path) FROM features WHERE project_id = ?"))
                .isGreaterThan(BTREE_ENTRY_LIMIT);
    }

    @Test
    @DisplayName("D14 B-tree: a 1,000-code-point emoji tag is stored, rejected twice in a project, kept in another")
    void aLongTagIsStoredAndUniquePerProject() throws Exception {
        String tag = "@" + cycle(0x1F300, 0x1F300 + 999, 999);
        assertThat(tag.codePointCount(0, tag.length())).isEqualTo(1000);

        insertTag(project, tag);
        assertThat(uniqueViolation(() -> insertTag(project, tag))).isEqualTo("uk_tags_project_id_name");
        insertTag(UUID.randomUUID(), tag);

        assertThat(octets("SELECT octet_length(name) FROM tags WHERE project_id = ?")).isGreaterThan(BTREE_ENTRY_LIMIT);
    }

    @Test
    @DisplayName("D12: tags are compared exactly, so @Smoke and @smoke are two tags")
    void tagsAreCaseSensitive() throws Exception {
        insertTag(project, "@smoke");
        insertTag(project, "@Smoke");

        assertThat(count("SELECT count(*) FROM tags WHERE project_id = ?", project)).isEqualTo(2);
    }

    /**
     * {@code count} code points, cycling through {@code first..last} (inclusive).
     */
    private static String cycle(int first, int last, int count) {
        StringBuilder text = new StringBuilder();
        IntStream.range(0, count).forEach(i -> text.appendCodePoint(first + i % (last - first + 1)));
        return text.toString();
    }

    private void insertFeature(UUID projectId, String path, Instant archivedAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO features (id, project_id, path, name, "
                + "description, language, current_version, created_by, updated_by, archived_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, '', '', 'en', 1, ?, ?, ?, ?, ?)")) {
            Timestamp now = Timestamp.from(Instant.parse("2026-10-18T09:00:00Z"));
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, projectId);
            insert.setString(3, path);
            insert.setObject(4, user);
            insert.setObject(5, user);
            insert.setTimestamp(6, archivedAt == null ? null : Timestamp.from(archivedAt));
            insert.setTimestamp(7, now);
            insert.setTimestamp(8, now);
            insert.executeUpdate();
        }
    }

    private void insertTag(UUID projectId, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO tags (id, project_id, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, projectId);
            insert.setString(3, name);
            insert.executeUpdate();
        }
    }

    /**
     * Runs the insert under a savepoint and returns the name of the unique index it violated.
     */
    private String uniqueViolation(SqlAction insert) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        SQLException failure = catchThrowableOfType(SQLException.class, insert::run);
        connection.rollback(savepoint);
        assertThat((Throwable) failure).as("a unique violation").isNotNull();
        assertThat(failure.getSQLState()).isEqualTo("23505");
        Matcher constraint = CONSTRAINT.matcher(failure.getMessage());
        assertThat(constraint.find()).as("the constraint name in the server message").isTrue();
        return constraint.group(1);
    }

    private long count(String sql, UUID projectId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, projectId);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long octets(String sql) throws SQLException {
        return count(sql, project);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
