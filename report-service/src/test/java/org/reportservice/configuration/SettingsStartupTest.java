package org.reportservice.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.ReportServiceApplication;
import org.reportservice.support.ScratchDatabase;
import org.reportservice.support.TestSettings;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AC-7.1 (D15): start-up refuses a missing or bad setting with a message that names it and contains no setting value.
 * Each start uses a scratch database (database pattern item 15), passes its settings as command-line arguments, binds
 * to {@code 127.0.0.1} on a free port, clears the active profile (so no {@code .env} is ever read) and leaves the
 * process environment out, so a developer's real variables can't make a row pass by accident.
 */
@ExtendWith(OutputCaptureExtension.class)
class SettingsStartupTest {

    /** Values that must never be printed: a (wrong) database password and username. */
    private static final String MARKER_PASSWORD = "Report-Db-Pw-Marker-QA";
    private static final String MARKER_USERNAME = "report_user_marker_qa";

    /** Standard Base64 of 32 bytes: a usable service key, but too short for an HS512 JWT secret. */
    private static final String BASE64_32_BYTES = base64("thirty-two bytes of TEST secret!");
    /** Standard Base64 of 16 bytes: too short for the service key. */
    private static final String BASE64_16_BYTES = base64("16 bytes of TEST");

    @ParameterizedTest(name = "AC-7.1 {0} {1}")
    @CsvSource(delimiter = '|', value = {
            "REPORT_DB_PASSWORD   | missing",
            "REPORT_DB_URL        | \"${REPORT_DB_URL}\"",
            "JWT_SECRET           | 32 bytes of Base64",
            "INTERNAL_SERVICE_KEY | missing",
            "INTERNAL_SERVICE_KEY | \"not base64!\"",
            "INTERNAL_SERVICE_KEY | 16 bytes of Base64"})
    @Tag("AC-7.1")
    void aMissingOrBadSettingStopsStartUpByName(String setting, String state, CapturedOutput output) throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given: valid settings for the scratch database, then the row's change
            Map<String, String> settings = validSettings(scratch);
            String value = switch (state) {
                case "missing" -> null;
                case "\"${REPORT_DB_URL}\"" -> "${REPORT_DB_URL}";
                case "32 bytes of Base64" -> BASE64_32_BYTES;
                case "\"not base64!\"" -> "not base64!";
                default -> BASE64_16_BYTES;
            };
            if (value == null) {
                settings.remove(setting);
            } else {
                settings.put(setting, value);
            }
            if (!setting.startsWith("REPORT_DB_")) {
                // The database is reachable for these rows, so a wrong database setting can't be what fails
                settings.put("REPORT_DB_USERNAME", scratch.username());
                settings.put("REPORT_DB_PASSWORD", scratch.password());
            }
            int logStart = output.getAll().length();

            // When
            Throwable failure = catchThrowable(() -> start(settings).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains(setting));
            // The container's own password is Testcontainers' public default ("test"), so it can't be a marker
            List<String> secrets = new ArrayList<>(List.of(MARKER_PASSWORD, MARKER_USERNAME,
                    TestSettings.JWT_SECRET, TestSettings.INTERNAL_SERVICE_KEY));
            if (value != null && !value.startsWith("${")) {
                secrets.add(value);
            }
            String log = output.getAll().substring(logStart);
            for (String secret : secrets) {
                assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(secret));
                assertThat(log).doesNotContain(secret);
            }
        }
    }

    @Test
    @Tag("AC-7.1")
    @DisplayName("AC-7.1 With every setting valid, start-up succeeds and Flyway applies V1")
    void validSettingsStartTheService(CapturedOutput output) throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            Map<String, String> settings = validSettings(scratch);
            settings.put("REPORT_DB_USERNAME", scratch.username());
            settings.put("REPORT_DB_PASSWORD", scratch.password());
            int logStart = output.getAll().length();

            try (ConfigurableApplicationContext app = start(settings)) {
                assertThat(app.isRunning()).isTrue();
                assertThat(history(scratch)).containsExactly("1 true");
            }
            assertThat(output.getAll().substring(logStart))
                    .doesNotContain(TestSettings.JWT_SECRET, TestSettings.INTERNAL_SERVICE_KEY);
        }
    }

    /** Valid settings for the scratch database, except for database credentials that must never be printed. */
    private static Map<String, String> validSettings(ScratchDatabase scratch) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("REPORT_DB_URL", scratch.url());
        values.put("REPORT_DB_USERNAME", MARKER_USERNAME);
        values.put("REPORT_DB_PASSWORD", MARKER_PASSWORD);
        values.put("JWT_SECRET", TestSettings.JWT_SECRET);
        values.put("INTERNAL_SERVICE_KEY", TestSettings.INTERNAL_SERVICE_KEY);
        return values;
    }

    /** Starts report-service without the process environment and without a profile, on 127.0.0.1. */
    private static ConfigurableApplicationContext start(Map<String, String> settings) {
        List<String> arguments = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0",
                "--spring.profiles.active="));
        settings.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(ReportServiceApplication.class).environment(environment)
                .run(arguments.toArray(String[]::new));
    }

    /** One line per migration history row, in installed order: version and success. */
    private static List<String> history(ScratchDatabase scratch) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = scratch.connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version || ' ' || success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return messages;
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }
}
