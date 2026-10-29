package org.reportservice.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.reportservice.support.TestDatabase.POSTGRES;

/**
 * An empty database on the test server that one start-up test creates, uses and drops (database pattern item 15). It
 * has the test database's encoding and locale. Use it in try-with-resources, so it's dropped in every case. It exists
 * only inside the test container.
 */
public final class ScratchDatabase implements AutoCloseable {

    private final String name;

    private ScratchDatabase(String name) {
        this.name = name;
    }

    public static ScratchDatabase create() throws SQLException {
        String name = "scratch_" + UUID.randomUUID().toString().replace("-", "");
        admin("CREATE DATABASE " + name
                + " TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8'");
        return new ScratchDatabase(name);
    }

    public String name() {
        return name;
    }

    public String url() {
        int port = POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + port + "/" + name;
    }

    public String username() {
        return POSTGRES.getUsername();
    }

    public String password() {
        return POSTGRES.getPassword();
    }

    /** A new connection to this database as the container's superuser; the caller closes it. */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), username(), password());
    }

    @Override
    public void close() throws SQLException {
        admin("DROP DATABASE " + name + " WITH (FORCE)");
    }

    /** Runs one statement as the container's superuser on its default database (scratch set-up only). */
    public static void admin(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
