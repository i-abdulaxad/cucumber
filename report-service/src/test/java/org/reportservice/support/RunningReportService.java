package org.reportservice.support;

import org.reportservice.ReportServiceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.SQLException;

/**
 * A running report-service for real-server tests (as scenario-service's, platform-hardening D9): its own application
 * on a {@link ScratchDatabase}, on a random port of {@code 127.0.0.1} (see {@link HttpCalls}), with the TEST-ONLY
 * settings, no profile, and the auth-service stub's address. A test class starts it once and closes it afterwards, so
 * it adds no cached test context. Close it in every case: closing stops the application and drops the database.
 */
public final class RunningReportService implements AutoCloseable {

    private final ScratchDatabase database;
    private final ConfigurableApplicationContext app;
    private final HttpCalls http;

    private RunningReportService(ScratchDatabase database, ConfigurableApplicationContext app) {
        this.database = database;
        this.app = app;
        this.http = new HttpCalls(Integer.parseInt(app.getEnvironment().getProperty("local.server.port")));
    }

    public static RunningReportService start() throws SQLException {
        ScratchDatabase database = ScratchDatabase.create();
        try {
            ConfigurableApplicationContext app = new SpringApplicationBuilder(ReportServiceApplication.class).run(
                    "--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=",
                    "--JWT_SECRET=" + TestSettings.JWT_SECRET,
                    "--INTERNAL_SERVICE_KEY=" + TestSettings.INTERNAL_SERVICE_KEY,
                    "--app.auth-service.base-url=" + TestSettings.authServiceBaseUrl(),
                    "--REPORT_DB_URL=" + database.url(), "--REPORT_DB_USERNAME=" + database.username(),
                    "--REPORT_DB_PASSWORD=" + database.password(),
                    "--spring.datasource.hikari.data-source-properties.options=-c lock_timeout=1s");
            return new RunningReportService(database, app);
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
