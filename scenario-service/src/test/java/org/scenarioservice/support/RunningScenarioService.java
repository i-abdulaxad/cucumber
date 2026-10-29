package org.scenarioservice.support;

import org.scenarioservice.ScenarioServiceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.SQLException;

/**
 * A running scenario-service for the real-server tests of platform-hardening (D9): its own application on a
 * {@link ScratchDatabase}, on a random port of {@code 127.0.0.1} (see {@link HttpCalls}), with the TEST-ONLY secret,
 * no profile, and the access check stub's address. A test class starts it once and closes it afterwards, so it adds
 * no cached test context. Close it in every case: closing stops the application and drops the scratch database.
 */
public final class RunningScenarioService implements AutoCloseable {

    private final ScratchDatabase database;
    private final ConfigurableApplicationContext app;
    private final HttpCalls http;

    private RunningScenarioService(ScratchDatabase database, ConfigurableApplicationContext app) {
        this.database = database;
        this.app = app;
        this.http = new HttpCalls(Integer.parseInt(app.getEnvironment().getProperty("local.server.port")));
    }

    public static RunningScenarioService start() throws SQLException {
        ScratchDatabase database = ScratchDatabase.create();
        try {
            ConfigurableApplicationContext app = new SpringApplicationBuilder(ScenarioServiceApplication.class).run(
                    "--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=",
                    "--JWT_SECRET=" + TestSettings.JWT_SECRET,
                    "--app.auth-service.base-url=" + TestSettings.accessCheckBaseUrl(),
                    "--SCENARIO_DB_URL=" + database.url(), "--SCENARIO_DB_USERNAME=" + database.username(),
                    "--SCENARIO_DB_PASSWORD=" + database.password(),
                    "--spring.datasource.hikari.data-source-properties.options=-c lock_timeout=1s");
            return new RunningScenarioService(database, app);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    public HttpCalls http() {
        return http;
    }

    @Override
    public void close() throws SQLException {
        try {
            app.close();
        } finally {
            database.close();
        }
    }
}
