package com.example.authservice.qa;

import com.example.authservice.AuthServiceApplication;
import com.example.authservice.support.ScratchDatabase;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qa, phase 4 US-4 (D6, D7; adversarial): the whole service runs in a JVM whose default time zone is far from UTC
 * from the very start, so every pooled connection also gets that session time zone. Timestamps written and read back
 * through the API, and stored in the database, are still the same UTC instants. St. John's has a half-hour offset
 * (UTC-02:30 in October), Kiritimati the largest one (UTC+14, a different calendar date). It runs on a scratch
 * database, because the shared test context's pool is already open in the build machine's zone.
 */
class QaTimeZoneTest {

    private static final String UTC_TIMESTAMP = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z$";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @ParameterizedTest(name = "qa AC-4.1/4.2 JVM and session time zone {0} from start-up")
    @ValueSource(strings = {"America/St_Johns", "Pacific/Kiritimati"})
    @Tag("AC-4.2")
    void aNonUtcDefaultZoneFromStartUpChangesNoInstant(String zone) throws Exception {
        TimeZone saved = TimeZone.getDefault();
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(AuthServiceApplication.class).run(
                    "--server.port=0", "--spring.profiles.active=", "--app.jwtSecret=" + TestJwt.SECRET,
                    "--AUTH_DB_URL=" + scratch.url(), "--AUTH_DB_USERNAME=" + scratch.username(),
                    "--AUTH_DB_PASSWORD=" + scratch.password())) {
                JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SHOW TimeZone", String.class)).as("session zone").isEqualTo(zone);
                List<String> timestamps = new ArrayList<>();

                // When
                HttpResponse<String> registered = call(app, "POST", "/api/auth/register",
                        "{\"email\":\"ben@example.com\",\"password\":\"Secret123!\",\"fullName\":\"Ben Ode\"}", null);
                timestamps.add(JsonPath.read(registered.body(), "$.createdAt"));
                String ben = JsonPath.read(call(app, "POST", "/api/auth/login",
                        "{\"email\":\"ben@example.com\",\"password\":\"Secret123!\"}", null).body(), "$.accessToken");
                HttpResponse<String> created = call(app, "POST", "/api/orgs", "{\"name\":\"Zone QA\"}", ben);
                String org = JsonPath.read(created.body(), "$.id");
                String createdAt = JsonPath.read(created.body(), "$.createdAt");
                HttpResponse<String> invited = call(app, "POST", "/api/orgs/" + org + "/invitations",
                        "{\"email\":\"kit@example.com\",\"role\":\"MEMBER\"}", ben);
                HttpResponse<String> accepted = call(app, "POST", "/api/auth/invitations/"
                                + JsonPath.read(invited.body(), "$.token") + "/accept",
                        "{\"password\":\"Secret123!\",\"fullName\":\"Kit Hale\"}", null);
                HttpResponse<String> project = call(app, "POST", "/api/orgs/" + org + "/projects",
                        "{\"name\":\"Zone Project QA\"}", ben);
                HttpResponse<String> archived = call(app, "POST", "/api/orgs/" + org + "/projects/"
                        + JsonPath.read(project.body(), "$.id") + "/archive", null, ben);
                HttpResponse<String> listed = call(app, "GET", "/api/orgs", null, ben);
                HttpResponse<String> members = call(app, "GET", "/api/orgs/" + org + "/members", null, ben);

                // Then
                assertThat(List.of(registered, created, invited, accepted, project, archived, listed, members))
                        .extracting(HttpResponse::statusCode).allMatch(status -> status / 100 == 2);
                timestamps.add(createdAt);
                timestamps.add(JsonPath.read(invited.body(), "$.createdAt"));
                timestamps.add(JsonPath.read(invited.body(), "$.expiresAt"));
                timestamps.add(JsonPath.read(accepted.body(), "$.user.createdAt"));
                timestamps.add(JsonPath.read(accepted.body(), "$.organization.createdAt"));
                timestamps.add(JsonPath.read(archived.body(), "$.archivedAt"));
                timestamps.addAll(JsonPath.read(members.body(), "$[*].joinedAt"));
                assertThat(timestamps).allSatisfy(timestamp -> assertThat(timestamp).matches(UTC_TIMESTAMP));

                assertThat(JsonPath.<List<String>>read(listed.body(), "$[*].createdAt"))
                        .as("read back after the commit").containsExactly(createdAt);
                assertThat(JsonPath.<String>read(accepted.body(), "$.organization.createdAt")).isEqualTo(createdAt);
                assertThat(Duration.between(Instant.parse(JsonPath.read(invited.body(), "$.createdAt")),
                        Instant.parse(JsonPath.read(invited.body(), "$.expiresAt")))).isEqualTo(Duration.ofDays(7));
                Instant stored = Instant.ofEpochSecond(0, Math.round(1_000_000_000L * jdbc.queryForObject(
                        "SELECT extract(epoch FROM created_at) FROM organizations", Double.class)));
                assertThat(Duration.between(stored, Instant.parse(createdAt)).abs())
                        .as("the stored instant").isLessThan(Duration.ofMillis(1));
                assertThat(Duration.between(Instant.parse(createdAt), Instant.now()).abs())
                        .as("the instant is now, not shifted by the zone offset").isLessThan(Duration.ofMinutes(1));
            }
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private static HttpResponse<String> call(ConfigurableApplicationContext app, String method, String path,
                                             String json, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + app.getEnvironment().getProperty("local.server.port") + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
