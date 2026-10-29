package org.reportservice.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one PostgreSQL test database of a Gradle test JVM (D14, database pattern items 3 and 14): a {@code postgres:17}
 * container with UTF-8 and the builtin {@code C.UTF-8} locale, started once on first use and shared by every test.
 * Testcontainers' Ryuk container removes it when the JVM exits. A missing Docker daemon fails the first test that uses
 * it; nothing is skipped.
 * <p>
 * Spring test contexts import it ({@link ReportServiceIntegrationTest}), and {@link ServiceConnection} supplies the
 * connection, so no test names a URL or a password. Boot stops an imported container when the importing context
 * closes; contexts stay cached until the JVM exits, so no test may close one early (no {@code @DirtiesContext}).
 * <p>
 * Its {@code @DynamicPropertySource} sets the connection budget (item 16: the server accepts
 * {@value #MAX_CONNECTIONS} connections, each context's pool holds at most {@value #POOL_SIZE} and keeps 1 idle), a
 * 1 s {@code lock_timeout} (production keeps 5 s; item 12), and the {@code REPORT_DB_*} settings that
 * {@code application.yaml} binds, so the application's settings check runs in every test context.
 */
public final class TestDatabase {

    /** PostgreSQL 17, major version pinned (item 1). */
    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:17");

    static final int MAX_CONNECTIONS = 300;
    static final int POOL_SIZE = 12;

    @ServiceConnection
    public static final PostgreSQLContainer POSTGRES = start();

    private TestDatabase() {
    }

    @DynamicPropertySource
    static void connectionSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.datasource.hikari.data-source-properties.options", () -> "-c lock_timeout=1s");
        registry.add("REPORT_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("REPORT_DB_USERNAME", POSTGRES::getUsername);
        registry.add("REPORT_DB_PASSWORD", POSTGRES::getPassword);
    }

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS);
        container.start();
        return container;
    }
}
