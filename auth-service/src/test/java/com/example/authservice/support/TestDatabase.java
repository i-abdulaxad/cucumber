package com.example.authservice.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one PostgreSQL test database of a Gradle test JVM (phase-4 D2, pattern item 14): a {@code postgres:17}
 * container, started once on first use and shared by every test. It's created with UTF-8 and the builtin
 * {@code C.UTF-8} locale (D3, pattern item 3). Testcontainers' Ryuk container removes it when the JVM exits. A missing
 * Docker daemon fails the first test that uses it; nothing is skipped.
 * <p>
 * Spring test contexts get it with {@code @ImportTestcontainers(TestDatabase.class)} (on
 * {@link AuthServiceIntegrationTest}, and on the classes that don't use it): {@link ServiceConnection} supplies the
 * connection, so no test names a URL or a password. Boot stops an imported container when the context that imported
 * it closes. Test contexts stay cached until the JVM exits, so that only happens at the end of the run; no test may
 * close a context early ({@code @DirtiesContext} is not allowed, P21).
 * <p>
 * Tests wait at most 1 s for a lock, to keep the build short; production and dev keep 5 s (E82).
 * <p>
 * It also supplies the {@code AUTH_DB_*} settings that {@code application.yaml} binds, so the application's own
 * settings validation (E81) runs in every test context too.
 * <p>
 * Connection budget (D2, pattern item 16): the server accepts {@value #MAX_CONNECTIONS} connections, and each test
 * context's pool holds at most {@value #POOL_SIZE} (more than the 10 simultaneous requests of the concurrency tests)
 * and keeps 1 idle.
 */
public final class TestDatabase {

    /**
     * PostgreSQL 17, major version pinned (D3).
     */
    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:17");

    static final int MAX_CONNECTIONS = 300;
    static final int POOL_SIZE = 12;

    @ServiceConnection
    public static final PostgreSQLContainer POSTGRES = start();

    private TestDatabase() {
    }

    @DynamicPropertySource
    static void connectionPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.datasource.hikari.data-source-properties.options", () -> "-c lock_timeout=1s");
        registry.add("AUTH_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("AUTH_DB_USERNAME", POSTGRES::getUsername);
        registry.add("AUTH_DB_PASSWORD", POSTGRES::getPassword);
    }

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS);
        container.start();
        return container;
    }
}
