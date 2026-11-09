package org.scenarioservice.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.ScenarioServiceApplication;
import org.scenarioservice.support.ScratchDatabase;
import org.scenarioservice.support.TestSettings;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AC-8.12 ({@code SCENARIO_DB_*} rows) and AC-8.13 (D14, D20 #2; start-up tests on scratch databases, D15 item 15).
 * Every start passes its settings as command-line arguments, clears the active profile (so no {@code .env} is ever
 * read, G10) and leaves the process environment out, so a developer's real variables can't make a row pass by
 * accident. The {@code JWT_SECRET} rows come in T3.
 */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseStartupTest {

    /**
     * Marks values that must never be printed: a (wrong) password and a username.
     */
    private static final String MARKER_PASSWORD = "Scenario-Db-Pw-Marker-QA";
    private static final String MARKER_USERNAME = "scenario_user_marker_qa";

    @ParameterizedTest(name = "AC-8.12 {0}")
    @CsvSource(delimiter = '|', value = {
            "SCENARIO_DB_URL unset              | SCENARIO_DB_URL      | unset",
            "SCENARIO_DB_USERNAME set to \"\"     | SCENARIO_DB_USERNAME | empty",
            "SCENARIO_DB_PASSWORD set to \"   \"  | SCENARIO_DB_PASSWORD | spaces"})
    @Tag("AC-8.12")
    @DisplayName("AC-8.12 A missing or blank database setting stops start-up, naming it and never showing a value")
    void aMissingOrBlankSettingStopsStartUpByName(String row, String setting, String change, CapturedOutput output)
            throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given
            Map<String, String> values = markerSettings(scratch);
            switch (change) {
                case "unset" -> values.remove(setting);
                case "empty" -> values.put(setting, "");
                default -> values.put(setting, "   ");
            }
            int logStart = output.getAll().length();

            // When
            Throwable failure = catchThrowable(() -> start(values, List.of()).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message)
                    .contains("must be set through the " + setting + " environment variable"));
            assertNoValuePrinted(failure, output.getAll().substring(logStart));
        }
    }

    @Test
    @Tag("AC-8.12")
    @DisplayName("AC-8.12 SCENARIO_DB_PASSWORD set to exactly ${SCENARIO_DB_PASSWORD} is named; only the placeholder "
            + "text may appear")
    void aPasswordSetToItsOwnPlaceholderIsNamed(CapturedOutput output) throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            Map<String, String> values = markerSettings(scratch);
            values.put("SCENARIO_DB_PASSWORD", "${SCENARIO_DB_PASSWORD}");
            int logStart = output.getAll().length();

            Throwable failure = catchThrowable(() -> start(values, List.of()).close());

            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message)
                    .contains("SCENARIO_DB_PASSWORD"));
            assertNoValuePrinted(failure, output.getAll().substring(logStart));
        }
    }

    @Test
    @Tag("AC-8.12")
    @DisplayName("AC-8.12 With none of the three database settings, one failure names all three")
    void withoutAnyDatabaseSettingOneFailureNamesAllThree(CapturedOutput output) {
        int logStart = output.getAll().length();

        Throwable failure = catchThrowable(() -> start(Map.of(), List.of()).close());

        assertThat(failure).as("start-up failure").isNotNull();
        assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains(
                "spring.datasource.url must be set through the SCENARIO_DB_URL environment variable",
                "spring.datasource.username must be set through the SCENARIO_DB_USERNAME environment variable",
                "spring.datasource.password must be set through the SCENARIO_DB_PASSWORD environment variable"));
        assertThat(output.getAll().substring(logStart)).doesNotContain(TestSettings.JWT_SECRET);
    }

    @ParameterizedTest(name = "AC-8.13 the password {0} is accepted and never logged")
    @ValueSource(strings = {"Qa$Pw-61", "Pw$QA{61}", "Pw{QA}61$"})
    @Tag("AC-8.13")
    @DisplayName("AC-8.13 A database password with $ or { is accepted and never logged")
    void aPasswordWithDollarOrBraceIsAcceptedAndNeverLogged(String password, CapturedOutput output) throws Exception {
        String role = "scratch_role_" + UUID.randomUUID().toString().replace("-", "");
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        LogLevel rootFound = configuredLevel(logging, LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel serviceFound = configuredLevel(logging, "org.scenarioservice");
        int logStart = output.getAll().length();
        // The database is dropped here, not by ScratchDatabase.close(), because the role owns it and can only be
        // dropped after it
        ScratchDatabase scratch = ScratchDatabase.create();
        try {
            ScratchDatabase.admin("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            ScratchDatabase.admin("ALTER DATABASE " + scratch.name() + " OWNER TO " + role);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("SCENARIO_DB_URL", scratch.url());
            values.put("SCENARIO_DB_USERNAME", role);
            values.put("SCENARIO_DB_PASSWORD", password);

            // When: the root logger and the service's loggers at DEBUG
            try (ConfigurableApplicationContext app = start(values,
                    List.of("--logging.level.root=DEBUG", "--logging.level.org.scenarioservice=DEBUG"))) {
                // Then
                assertThat(app.isRunning()).isTrue();
                assertThat(history(scratch)).containsExactly("1 true");
            }
        } finally {
            // Log levels are JVM-global (W3): put back what the test found
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootFound);
            logging.setLogLevel("org.scenarioservice", serviceFound);
            ScratchDatabase.admin("DROP DATABASE " + scratch.name() + " WITH (FORCE)");
            ScratchDatabase.admin("DROP ROLE " + role);
        }
        assertThat(output.getAll().substring(logStart)).contains(" DEBUG ").doesNotContain(password);
    }

    /**
     * Valid settings for the scratch database, except for marker values that must never be printed.
     */
    private static Map<String, String> markerSettings(ScratchDatabase scratch) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("SCENARIO_DB_URL", scratch.url());
        values.put("SCENARIO_DB_USERNAME", MARKER_USERNAME);
        values.put("SCENARIO_DB_PASSWORD", MARKER_PASSWORD);
        return values;
    }

    private static void assertNoValuePrinted(Throwable failure, String log) {
        for (String value : List.of(MARKER_PASSWORD, MARKER_USERNAME, TestSettings.JWT_SECRET)) {
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(value));
            assertThat(log).doesNotContain(value);
        }
    }

    /**
     * Starts scenario-service without the process environment and without a profile, with the given settings.
     */
    private static ConfigurableApplicationContext start(Map<String, String> settings, List<String> extra) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=",
                "--JWT_SECRET=" + TestSettings.JWT_SECRET));
        settings.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        arguments.addAll(extra);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(ScenarioServiceApplication.class).environment(environment)
                .run(arguments.toArray(String[]::new));
    }

    private static LogLevel configuredLevel(LoggingSystem logging, String logger) {
        LoggerConfiguration configuration = logging.getLoggerConfiguration(logger);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    /**
     * One line per migration history row, in installed order: version and success.
     */
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
}
