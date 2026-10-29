package com.example.authservice.support;

import com.example.authservice.AuthServiceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A running auth-service for the real-server tests of platform-hardening (D9): its own application on a
 * {@link ScratchDatabase}, on a random port of {@code 127.0.0.1} (see {@link HttpCalls}), with the TEST-ONLY secret and
 * no profile. A test class starts it once and closes it afterwards, so it adds no cached test context. Close it in
 * every case: closing stops the application and drops the scratch database.
 */
public final class RunningAuthService implements AutoCloseable {

    private final ScratchDatabase database;
    private final ConfigurableApplicationContext app;
    private final HttpCalls http;

    private RunningAuthService(ScratchDatabase database, ConfigurableApplicationContext app) {
        this.database = database;
        this.app = app;
        this.http = new HttpCalls(Integer.parseInt(app.getEnvironment().getProperty("local.server.port")));
    }

    /** Starts the application; {@code extraArguments} are further {@code --name=value} settings. */
    public static RunningAuthService start(String... extraArguments) throws SQLException {
        ScratchDatabase database = ScratchDatabase.create();
        try {
            List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1",
                    "--spring.profiles.active=", "--app.jwtSecret=" + TestJwt.SECRET,
                    "--AUTH_DB_URL=" + database.url(), "--AUTH_DB_USERNAME=" + database.username(),
                    "--AUTH_DB_PASSWORD=" + database.password(),
                    "--spring.datasource.hikari.data-source-properties.options=-c lock_timeout=1s"));
            arguments.addAll(List.of(extraArguments));
            ConfigurableApplicationContext app = new SpringApplicationBuilder(AuthServiceApplication.class)
                    .run(arguments.toArray(String[]::new));
            return new RunningAuthService(database, app);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    public HttpCalls http() {
        return http;
    }

    public <T> T bean(Class<T> type) {
        return app.getBean(type);
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
