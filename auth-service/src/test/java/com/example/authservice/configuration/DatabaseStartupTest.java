package com.example.authservice.configuration;

import com.example.authservice.AuthServiceApplication;
import com.example.authservice.support.ScratchDatabase;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * US-1 (phase 4, T3): auth-service runs on PostgreSQL with a schema that only Flyway creates. Every scenario starts
 * auth-service the way {@code JwtSecretStartupTest} does ({@link SpringApplicationBuilder}, port 0, no profile) on a
 * {@link ScratchDatabase}, so a broken schema never touches the shared test database.
 */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseStartupTest {

    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__\\w+\\.sql");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String WRONG_PASSWORD = "Wrong-Pw-QA-61";
    private static final String FAY =
            "{\"email\":\"fay@example.com\",\"password\":\"Secret123!\",\"fullName\":\"Fay Lowe\"}";
    private static final String FAY_LOGIN = "{\"email\":\"fay@example.com\",\"password\":\"Secret123!\"}";

    @Test
    @Tag("AC-1.1")
    @DisplayName("AC-1.1 A fresh database is migrated at start-up")
    void aFreshDatabaseIsMigratedAtStartUp() throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // When
            try (ConfigurableApplicationContext app = start(scratch)) {
                // Then
                assertThat(history(scratch)).extracting(row -> row.split(" ")[0])
                        .as("every migration, in version order").containsExactlyElementsOf(migrationVersions());
                assertThat(history(scratch)).allSatisfy(row -> assertThat(row).endsWith(" true"));
                assertThat(tables(scratch)).containsExactlyInAnyOrder("invitations", "org_memberships",
                        "organizations", "project_memberships", "projects", "users", "flyway_schema_history");
                assertThat(post(app, "/api/auth/register", FAY, null).statusCode()).isEqualTo(201);
            }
        }
    }

    @Test
    @Tag("AC-1.2")
    @DisplayName("AC-1.2 Data survives a restart")
    void dataSurvivesARestart() throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given
            List<String> history;
            try (ConfigurableApplicationContext app = start(scratch)) {
                assertThat(post(app, "/api/auth/register", FAY, null).statusCode()).isEqualTo(201);
                String token = JsonPath.read(post(app, "/api/auth/login", FAY_LOGIN, null).body(), "$.accessToken");
                assertThat(post(app, "/api/orgs", "{\"name\":\"Fay Org QA\"}", token).statusCode()).isEqualTo(201);
                history = history(scratch);
            }

            // When
            try (ConfigurableApplicationContext app = start(scratch)) {
                // Then
                assertThat(history(scratch)).isEqualTo(history);
                HttpResponse<String> login = post(app, "/api/auth/login", FAY_LOGIN, null);
                assertThat(login.statusCode()).isEqualTo(200);
                String token = JsonPath.read(login.body(), "$.accessToken");
                HttpResponse<String> orgs = get(app, "/api/orgs", token);
                assertThat(orgs.statusCode()).isEqualTo(200);
                assertThat(JsonPath.<List<String>>read(orgs.body(), "$[*].name")).containsExactly("Fay Org QA");
            }
        }
    }

    @ParameterizedTest(name = "AC-1.3 {0}")
    @CsvSource(delimiter = '|', value = {
            "column projects.archived_at dropped | ALTER TABLE projects DROP COLUMN archived_at | archived_at",
            "table project_memberships dropped   | DROP TABLE project_memberships             | project_memberships"})
    @Tag("AC-1.3")
    @DisplayName("AC-1.3 The service refuses to start on a schema that doesn't match its entities")
    void theServiceRefusesToStartOnASchemaThatDoesNotMatchItsEntities(String drift, String sql, String object)
            throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given
            start(scratch).close();
            execute(scratch, sql);
            List<String> history = history(scratch);
            List<String> columns = columns(scratch);

            // When
            Throwable failure = catchThrowable(() -> start(scratch).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains(object));
            assertThat(history(scratch)).isEqualTo(history);
            assertThat(columns(scratch)).isEqualTo(columns);
        }
    }

    @Test
    @Tag("AC-1.4")
    @DisplayName("AC-1.4 A migration edited after it was applied stops the start-up")
    void aMigrationEditedAfterItWasAppliedStopsTheStartUp() throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given
            start(scratch).close();
            execute(scratch, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
            List<String> history = history(scratch);
            List<String> columns = columns(scratch);

            // When
            Throwable failure = catchThrowable(() -> start(scratch).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message)
                    .containsIgnoringCase("validat").containsPattern("version 1\\b"));
            assertThat(history(scratch)).isEqualTo(history);
            assertThat(columns(scratch)).isEqualTo(columns);
        }
    }

    @ParameterizedTest(name = "AC-1.5 {0} is {1}")
    @CsvSource(delimiter = '|', value = {
            "AUTH_DB_URL      | not set", "AUTH_DB_USERNAME | not set", "AUTH_DB_PASSWORD | not set",
            "AUTH_DB_URL      | blank", "AUTH_DB_USERNAME | blank", "AUTH_DB_PASSWORD | blank"})
    @Tag("AC-1.5")
    @DisplayName("AC-1.5 Without database settings, the service refuses to start")
    void withoutDatabaseSettingsTheServiceRefusesToStart(String setting, String state, CapturedOutput output)
            throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // Given: the process environment is left out, so a developer's real AUTH_DB_* variables can't make a row
            // pass by accident; the settings the row doesn't test point at the scratch database, with the AC-1.6
            // password as the marker of a value that must never be printed
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AUTH_DB_URL", scratch.url());
            values.put("AUTH_DB_USERNAME", scratch.username());
            values.put("AUTH_DB_PASSWORD", WRONG_PASSWORD);
            if (state.equals("blank")) {
                values.put(setting, "   ");
            } else {
                values.remove(setting);
            }
            List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=",
                    "--app.jwtSecret=" + TestJwt.SECRET));
            values.forEach((name, value) -> arguments.add("--" + name + "=" + value));
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            int logStart = output.getAll().length();

            // When
            Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(AuthServiceApplication.class)
                    .environment(environment).run(arguments.toArray(String[]::new)).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains(setting));
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(WRONG_PASSWORD));
            assertThat(output.getAll().substring(logStart)).doesNotContain(WRONG_PASSWORD);
        }
    }

    @ParameterizedTest(name = "AC-1.6 (BUG-1) the password {0} passes the settings check and is never printed")
    @ValueSource(strings = {"Qa${Pw-61", "Pw${QA}61", "Pw$QA{61}"})
    @Tag("AC-1.6")
    @DisplayName("AC-1.6 (BUG-1) a password containing $ or { is a set value, and a failed start never prints it")
    void aPasswordContainingPlaceholderCharactersIsASetValueAndNeverPrinted(String password, CapturedOutput output)
            throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            int logStart = output.getAll().length();

            // When: the password is wrong for the scratch database
            Throwable failure = catchThrowable(() -> startWithoutEnvironment(Map.of("AUTH_DB_URL", scratch.url(),
                    "AUTH_DB_USERNAME", scratch.username(), "AUTH_DB_PASSWORD", password)).close());

            // Then: the settings check accepts it, so start-up fails only on the connection
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message)
                    .contains("password authentication failed"));
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains("must be set"));
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(password));
            assertThat(output.getAll().substring(logStart)).doesNotContain(password);
        }
    }

    @ParameterizedTest(name = "AC-1.5 (BUG-1) {0} that is exactly one unresolved placeholder is rejected by name")
    @CsvSource({"AUTH_DB_URL", "AUTH_DB_USERNAME", "AUTH_DB_PASSWORD"})
    @Tag("AC-1.5")
    @DisplayName("AC-1.5 (BUG-1) a setting that is exactly one unresolved ${NAME} is rejected by name, without a value")
    void aSettingThatIsExactlyOneUnresolvedPlaceholderIsRejected(String setting, CapturedOutput output)
            throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            Map<String, String> values = settings(scratch);
            values.put(setting, "${AUTH_SERVICE_TEST_VARIABLE_THAT_IS_NEVER_SET}");
            int logStart = output.getAll().length();

            // When
            Throwable failure = catchThrowable(() -> startWithoutEnvironment(values).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message)
                    .contains(setting).contains("must be set"));
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(WRONG_PASSWORD));
            assertThat(output.getAll().substring(logStart)).doesNotContain(WRONG_PASSWORD).doesNotContain("Value: ")
                    .doesNotContain("AUTH_SERVICE_TEST_VARIABLE_THAT_IS_NEVER_SET");
        }
    }

    @ParameterizedTest(name = "AC-1.5 (BUG-1) {0} set to its own placeholder is named, and no other value is printed")
    @CsvSource({"AUTH_DB_URL", "AUTH_DB_USERNAME", "AUTH_DB_PASSWORD"})
    @Tag("AC-1.5")
    @DisplayName("AC-1.5 (BUG-1) a setting set to exactly its own ${AUTH_DB_*} placeholder is rejected by name")
    void aSettingSetToItsOwnPlaceholderIsRejectedByName(String setting, CapturedOutput output) throws Exception {
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            Map<String, String> values = settings(scratch);
            values.put(setting, "${" + setting + "}");
            int logStart = output.getAll().length();

            // When: Spring's own placeholder resolution reports the circular reference, naming the variable
            Throwable failure = catchThrowable(() -> startWithoutEnvironment(values).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains(setting));
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(WRONG_PASSWORD));
            assertThat(output.getAll().substring(logStart)).doesNotContain(WRONG_PASSWORD);
        }
    }

    /**
     * Valid settings for the scratch database, with the AC-1.6 marker as a (wrong) password.
     */
    private static Map<String, String> settings(ScratchDatabase scratch) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AUTH_DB_URL", scratch.url());
        values.put("AUTH_DB_USERNAME", scratch.username());
        values.put("AUTH_DB_PASSWORD", WRONG_PASSWORD);
        return values;
    }

    @Test
    @Tag("AC-1.6")
    @DisplayName("AC-1.6 A wrong database password stops the start-up without being printed")
    void aWrongDatabasePasswordStopsTheStartUpWithoutBeingPrinted(CapturedOutput output) throws Exception {
        String wrongPassword = WRONG_PASSWORD;
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            // When
            Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(AuthServiceApplication.class)
                    .run("--server.port=0", "--spring.profiles.active=", "--app.jwtSecret=" + TestJwt.SECRET,
                            "--AUTH_DB_URL=" + scratch.url(), "--AUTH_DB_USERNAME=" + scratch.username(),
                            "--AUTH_DB_PASSWORD=" + wrongPassword)
                    .close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(wrongPassword));
            assertThat(output.getAll()).doesNotContain(wrongPassword);
        }
    }

    /**
     * Starts auth-service without the process environment, with the given AUTH_DB_* values.
     */
    private static ConfigurableApplicationContext startWithoutEnvironment(Map<String, String> values) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=",
                "--app.jwtSecret=" + TestJwt.SECRET));
        values.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(AuthServiceApplication.class).environment(environment)
                .run(arguments.toArray(String[]::new));
    }

    private static ConfigurableApplicationContext start(ScratchDatabase scratch) {
        return new SpringApplicationBuilder(AuthServiceApplication.class)
                .run("--server.port=0", "--spring.profiles.active=", "--app.jwtSecret=" + TestJwt.SECRET,
                        "--AUTH_DB_URL=" + scratch.url(), "--AUTH_DB_USERNAME=" + scratch.username(),
                        "--AUTH_DB_PASSWORD=" + scratch.password());
    }

    /**
     * The versions of the migration files on the classpath, in version order.
     */
    private static List<String> migrationVersions() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        return Arrays.stream(files)
                .map(file -> MIGRATION_FILE.matcher(String.valueOf(file.getFilename())))
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();
    }

    /**
     * One line per migration history row, in installed order: version, checksum, success.
     */
    private static List<String> history(ScratchDatabase scratch) throws SQLException {
        return query(scratch, "SELECT version || ' ' || checksum || ' ' || success FROM flyway_schema_history "
                + "ORDER BY installed_rank");
    }

    private static List<String> tables(ScratchDatabase scratch) throws SQLException {
        return query(scratch, "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'");
    }

    private static List<String> columns(ScratchDatabase scratch) throws SQLException {
        return query(scratch, "SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable "
                + "FROM information_schema.columns WHERE table_schema = 'public' ORDER BY 1");
    }

    private static List<String> query(ScratchDatabase scratch, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = scratch.connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static void execute(ScratchDatabase scratch, String sql) throws SQLException {
        try (Connection connection = scratch.connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext app, String path, String json, String token)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(app, path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext app, String path, String token)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(app, path)).header("Authorization", "Bearer " + token)
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(ConfigurableApplicationContext app, String path) {
        return URI.create("http://localhost:" + app.getEnvironment().getProperty("local.server.port") + path);
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return messages;
    }
}
