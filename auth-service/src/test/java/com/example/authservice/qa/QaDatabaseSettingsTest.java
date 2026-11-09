package com.example.authservice.qa;

import com.example.authservice.AuthServiceApplication;
import com.example.authservice.configuration.DatabaseProperties;
import com.example.authservice.configuration.DatabaseSettingsCheck;
import com.example.authservice.support.ScratchDatabase;
import com.example.authservice.support.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.authservice.support.TestDatabase.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * qa, phase 4 AC-1.5 and AC-1.6 (E81, E83; adversarial): missing or blank {@code AUTH_DB_*} settings stop start-up
 * with a failure that names every missing setting at once, for blank values made of tabs or line breaks too, and never
 * prints the values that were set. A value that only contains {@code $}, <code>{</code> or <code>${</code> is a set
 * value (BUG-1): the service starts with such a password against a real role, and never prints it. The process
 * environment is left out, so a developer's real variables can't make a case pass by accident.
 */
@ExtendWith(OutputCaptureExtension.class)
class QaDatabaseSettingsTest {

    private static final String MARKER_PASSWORD = "Qa-Db-Pw-Marker-61";
    /**
     * The three BUG-1 passwords in one, so that one real start-up proves all of them.
     */
    private static final String DOLLAR_BRACE_PASSWORD = "Qa${Pw-61 Pw${QA}61 Pw$QA{61}";

    @Test
    @Tag("AC-1.5")
    @DisplayName("qa AC-1.5: with none of the three settings, the failure names all three")
    void withoutAnySettingTheFailureNamesAllThree() {
        Throwable failure = start(Map.of());

        assertThat(failure).isNotNull();
        assertThat(messages(failure)).anySatisfy(message -> assertThat(message).contains(
                "spring.datasource.url must be set through the AUTH_DB_URL environment variable",
                "spring.datasource.username must be set through the AUTH_DB_USERNAME environment variable",
                "spring.datasource.password must be set through the AUTH_DB_PASSWORD environment variable"));
    }

    @ParameterizedTest(name = "qa AC-1.5 {0} made of {1} is named and no set value is printed")
    @CsvSource(delimiter = '|', value = {
            "AUTH_DB_URL | a tab", "AUTH_DB_USERNAME | a line break", "AUTH_DB_PASSWORD | a tab and a line break",
            "AUTH_DB_URL | the empty string"})
    @Tag("AC-1.5")
    void aBlankSettingIsNamedAndNoSetValueIsPrinted(String setting, String blank, CapturedOutput output)
            throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AUTH_DB_URL", scratch.url());
            values.put("AUTH_DB_USERNAME", "qa_user_marker");
            values.put("AUTH_DB_PASSWORD", MARKER_PASSWORD);
            values.put(setting, switch (blank) {
                case "a tab" -> "\t";
                case "a line break" -> "\n";
                case "a tab and a line break" -> "\t\r\n";
                default -> "";
            });
            int logStart = output.getAll().length();

            // When
            Throwable failure = start(values);

            // Then
            assertThat(failure).isNotNull();
            assertThat(messages(failure)).anySatisfy(message -> assertThat(message)
                    .contains("must be set through the " + setting + " environment variable"));
            assertThat(messages(failure)).noneSatisfy(message -> assertThat(message).contains(MARKER_PASSWORD));
            assertThat(output.getAll().substring(logStart)).doesNotContain(MARKER_PASSWORD);
        }
    }

    @ParameterizedTest(name = "qa AC-1.6 (BUG-1) the settings check accepts the set value \"{0}\"")
    @ValueSource(strings = {"Qa${Pw-61", "Pw${QA}61", "Pw$QA{61}", DOLLAR_BRACE_PASSWORD, "${", "}${", "$${A}",
            "${A}${B}", "x${A}"})
    @Tag("AC-1.6")
    void aValueThatOnlyContainsDollarOrBracesIsSet(String password) {
        assertThatCode(() -> new DatabaseSettingsCheck(new DatabaseProperties("jdbc:postgresql://h/db", "u",
                password))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "qa AC-1.5 (E83) the unresolved placeholder \"{0}\" is unset, and the message has no value")
    @ValueSource(strings = {"${AUTH_DB_PASSWORD}", "${QA_UNSET}", "${a.b-c_d}"})
    @Tag("AC-1.5")
    void exactlyOneUnresolvedPlaceholderIsUnset(String password) {
        Throwable failure = catchThrowable(() -> new DatabaseSettingsCheck(
                new DatabaseProperties("jdbc:postgresql://h/db", "u", password)));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(
                "spring.datasource.password must be set through the AUTH_DB_PASSWORD environment variable");
    }

    @Test
    @Tag("AC-1.6")
    @DisplayName("qa AC-1.6 (BUG-1): a role whose password contains $, { and ${ logs in; the service starts and "
            + "serves requests, and no log line or exception contains the password or any part of it")
    void theServiceStartsWithADollarBracePasswordAndNeverPrintsIt(CapturedOutput output) throws Exception {
        String role = "qa_pw_" + UUID.randomUUID().toString().replace("-", "");
        int logStart = output.getAll().length();
        // The scratch database is dropped here, not by ScratchDatabase.close(), because the role owns it and can
        // only be dropped after it
        ScratchDatabase scratch = ScratchDatabase.create();
        try {
            admin("CREATE ROLE " + role + " LOGIN PASSWORD '" + DOLLAR_BRACE_PASSWORD + "'");
            admin("ALTER DATABASE " + scratch.name() + " OWNER TO " + role);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AUTH_DB_URL", scratch.url());
            values.put("AUTH_DB_USERNAME", role);
            values.put("AUTH_DB_PASSWORD", DOLLAR_BRACE_PASSWORD);

            // When
            try (ConfigurableApplicationContext app = run(values)) {
                HttpResponse<String> registered = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + app.getEnvironment()
                                        .getProperty("local.server.port") + "/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"pw-qa@example.com\",\"password\":\"Secret123!\",\"fullName\":\"Pw QA\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());

                // Then
                assertThat(registered.statusCode()).isEqualTo(201);
            }
        } finally {
            admin("DROP DATABASE " + scratch.name() + " WITH (FORCE)");
            admin("DROP ROLE IF EXISTS " + role);
        }
        String log = output.getAll().substring(logStart);
        for (String part : List.of(DOLLAR_BRACE_PASSWORD, "Qa${Pw-61", "Pw${QA}61", "Pw$QA{61}")) {
            assertThat(log).doesNotContain(part);
        }
    }

    @Test
    @Tag("AC-1.6")
    @DisplayName("qa AC-1.6 (E83): a variable set to its own placeholder fails on the circular reference, naming the "
            + "variable, and prints only the placeholder text")
    void aVariableSetToItsOwnPlaceholderPrintsOnlyThePlaceholder(CapturedOutput output) throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            int logStart = output.getAll().length();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AUTH_DB_URL", scratch.url());
            values.put("AUTH_DB_USERNAME", "qa_user_marker");
            values.put("AUTH_DB_PASSWORD", "${AUTH_DB_PASSWORD}");

            Throwable failure = start(values);

            assertThat(failure).isNotNull();
            assertThat(messages(failure)).anySatisfy(message -> assertThat(message).contains("AUTH_DB_PASSWORD"));
            assertThat(output.getAll().substring(logStart)).contains("Value: \"${AUTH_DB_PASSWORD}\"")
                    .doesNotContain(MARKER_PASSWORD);
        }
    }

    private static Throwable start(Map<String, String> settings) {
        return catchThrowable(() -> run(settings).close());
    }

    private static ConfigurableApplicationContext run(Map<String, String> settings) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=",
                "--app.jwtSecret=" + TestJwt.SECRET));
        settings.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(AuthServiceApplication.class).environment(environment)
                .run(arguments.toArray(String[]::new));
    }

    private static void admin(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }
}
