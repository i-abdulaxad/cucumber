package com.example.authservice.qa;

import com.example.authservice.AuthServiceApplication;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.example.authservice.support.ScratchDatabase;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.members;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static com.example.authservice.support.TestDatabase.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, phase 4 AC-3.2 and D10 (adversarial): lock waits stay bounded and end in 409 CONFLICT beyond the spec's two
 * rows: every organization-scoped project and member write, a burst of requests that queue for the same lock and for
 * the connection pool, and a real deadlock (SQLState 40P01). Background: the Projects fixture; the shared test
 * database waits 1 s for a lock (E82).
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class QaLockTimeoutTest {

    private static final String LOCK_ORGANIZATION = "SELECT id FROM organizations WHERE id = ? FOR UPDATE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMembershipRepository projectMembershipRepository;

    private Projects projects;
    private ProjectCalls calls;

    @BeforeEach
    void seedProjects() {
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("qa AC-3.2: while Acme's row is locked, each organization-scoped project and member write is a 409 "
            + "CONFLICT and changes nothing")
    void everyOrganizationScopedWriteEndsInAConflict() throws Exception {
        // Given: the six writes run at the same moment behind one lock (FU-21: one 1 s wait instead of six)
        calls.login("ana", "cai");
        String acme = "/api/orgs/" + projects.acme().orgId();
        String checkout = project(projects.acme().orgId(), projects.checkout());
        String checkoutMembers = members(projects.acme().orgId(), projects.checkout());
        Map<String, Callable<MvcResult>> writes = new LinkedHashMap<>();
        writes.put("Ana creates a project", () -> calls.as("ana", post(projects(projects.acme().orgId())),
                "{\"name\":\"Locked QA\"}"));
        writes.put("Cai renames Checkout", () -> calls.as("cai", patch(checkout), "{\"name\":\"Locked QA\"}"));
        writes.put("Cai archives Checkout", () -> calls.as("cai", post(checkout + "/archive")));
        writes.put("Cai adds Ivy to Checkout", () -> calls.as("cai", post(checkoutMembers),
                "{\"userId\":\"" + projects.ivy().getId() + "\",\"role\":\"READER\"}"));
        writes.put("Cai changes Hal's project role", () -> calls.as("cai",
                patch(checkoutMembers + "/" + projects.hal().getId()), "{\"role\":\"MEMBER\"}"));
        writes.put("Ana changes Dan's organization role", () -> calls.as("ana",
                patch(acme + "/members/" + projects.acme().dan().getId()), "{\"role\":\"MEMBER\"}"));
        List<Object> before = state();

        // When
        List<MvcResult> results;
        Duration took;
        try (Connection holder = lock(projects.acme().orgId())) {
            long start = System.nanoTime();
            results = ProjectCalls.simultaneously(new ArrayList<>(writes.values()));
            took = Duration.ofNanos(System.nanoTime() - start);
            holder.rollback();
        }

        // Then
        assertThat(took).isLessThan(Duration.ofSeconds(7));
        SoftAssertions softly = new SoftAssertions();
        List<String> names = new ArrayList<>(writes.keySet());
        for (int i = 0; i < results.size(); i++) {
            MvcResult result = results.get(i);
            softly.assertThat(result.getResponse().getStatus()).as(names.get(i)).isEqualTo(409);
            softly.assertThat(result.getResponse().getContentAsString()).as(names.get(i))
                    .contains("\"code\":\"CONFLICT\"");
        }
        softly.assertAll();
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("qa AC-3.2/D2: 20 invitations queued behind one row lock all end in 409 CONFLICT, the pool recovers")
    void aBurstQueuedBehindOneLockAllEndInAConflictAndThePoolRecovers() throws Exception {
        // Given
        calls.login("ben");
        String invitations = "/api/orgs/" + projects.acme().orgId() + "/invitations";
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        long invitationsBefore = invitationRepository.count();
        List<Callable<MvcResult>> burst = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String body = "{\"email\":\"burst" + i + "-qa@example.com\",\"role\":\"MEMBER\"}";
            burst.add(() -> calls.as("ben", post(invitations), body));
        }

        // When
        List<MvcResult> results;
        long start = System.nanoTime();
        try (Connection holder = lock(projects.acme().orgId())) {
            results = ProjectCalls.simultaneously(burst);
            holder.rollback();
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        // Then
        for (MvcResult result : results) {
            assertProblem(result, 409, "CONFLICT", invitations);
        }
        assertThat(took).as("20 waits of at most 1 s over an 11-connection pool").isLessThan(Duration.ofSeconds(15));
        assertThat(invitationRepository.count()).isEqualTo(invitationsBefore);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).as("no connection leaked").isZero();
        assertThat(calls.as("ben", post(invitations), "{\"email\":\"after-qa@example.com\",\"role\":\"MEMBER\"}")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("qa D10: a real deadlock (40P01) between an anonymous acceptance and another transaction is a 409 "
            + "CONFLICT, well before the lock timeout")
    void aDeadlockIsAConflict() throws Exception {
        // Given: an app on a scratch database that checks for deadlocks after 200 ms and waits 5 s for a lock
        try (ScratchDatabase scratch = ScratchDatabase.create();
             ConfigurableApplicationContext app = new SpringApplicationBuilder(AuthServiceApplication.class).run(
                     "--server.port=0", "--spring.profiles.active=", "--app.jwtSecret=" + TestJwt.SECRET,
                     "--AUTH_DB_URL=" + scratch.url(), "--AUTH_DB_USERNAME=" + scratch.username(),
                     "--AUTH_DB_PASSWORD=" + scratch.password(),
                     "--spring.datasource.hikari.data-source-properties.options="
                             + "-c lock_timeout=5s -c deadlock_timeout=200ms")) {
            Http http = new Http(app);
            String ben = http.registerAndLogIn("ben@example.com");
            String org = JsonPath.read(http.call("POST", "/api/orgs", "{\"name\":\"Deadlock QA\"}", ben).body(), "$.id");
            String invitation = http.call("POST", "/api/orgs/" + org + "/invitations",
                    "{\"email\":\"kit@example.com\",\"role\":\"MEMBER\"}", ben).body();
            String token = JsonPath.read(invitation, "$.token");
            int logStart = POSTGRES.getLogs().length();

            try (Connection holder = scratch.connect()) {
                holder.setAutoCommit(false);
                try (Statement statement = holder.createStatement()) {
                    statement.execute("SET lock_timeout = '20s'");
                    statement.execute("SET deadlock_timeout = '20s'");
                }
                execute(holder, LOCK_ORGANIZATION, UUID.fromString(org));
                long start = System.nanoTime();

                // When: the acceptance locks the invitation and waits for the organization; the holder then waits
                // for the invitation
                CompletableFuture<HttpResponse<String>> accept = CompletableFuture.supplyAsync(() ->
                        http.unchecked("POST", "/api/auth/invitations/" + token + "/accept",
                                "{\"password\":\"Secret123!\",\"fullName\":\"Kit Hale\"}"));
                Thread.sleep(100);
                execute(holder, "SELECT id FROM invitations WHERE id = ? FOR UPDATE",
                        UUID.fromString(JsonPath.read(invitation, "$.id")));
                HttpResponse<String> response = accept.get();
                Duration took = Duration.ofNanos(System.nanoTime() - start);
                holder.rollback();

                // Then
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("CONFLICT");
                assertThat(took).as("ended by the deadlock check, not the 5 s lock timeout")
                        .isLessThan(Duration.ofSeconds(3));
                assertThat(POSTGRES.getLogs().substring(logStart)).contains("deadlock detected");
                assertThat(http.call("POST", "/api/auth/login",
                        "{\"email\":\"kit@example.com\",\"password\":\"Secret123!\"}", null).statusCode())
                        .as("no account was created").isEqualTo(401);
            }
        }
    }

    private Connection lock(UUID orgId) throws Exception {
        Connection holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        execute(holder, LOCK_ORGANIZATION, orgId);
        return holder;
    }

    private static void execute(Connection connection, String sql, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeQuery().close();
        }
    }

    private List<Object> state() {
        return List.of(projectRepository.count(), projectMembershipRepository.count(), invitationRepository.count(),
                projectRepository.findById(projects.checkout()).orElseThrow().getName(),
                String.valueOf(projectRepository.findById(projects.checkout()).orElseThrow().getArchivedAt()),
                projectMembershipRepository.findByUserIdAndProjectId(projects.hal().getId(), projects.checkout())
                        .orElseThrow().getRole());
    }

    /**
     * Plain HTTP calls to an app started on a scratch database.
     */
    private record Http(ConfigurableApplicationContext app) {

        private static final HttpClient CLIENT = HttpClient.newHttpClient();

        HttpResponse<String> call(String method, String path, String json, String token) throws Exception {
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
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> unchecked(String method, String path, String json) {
            try {
                return call(method, path, json, null);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        String registerAndLogIn(String email) throws Exception {
            String credentials = "\"email\":\"" + email + "\",\"password\":\"Secret123!\"";
            assertThat(call("POST", "/api/auth/register", "{" + credentials + ",\"fullName\":\"Ben QA\"}", null)
                    .statusCode()).isEqualTo(201);
            return JsonPath.read(call("POST", "/api/auth/login", "{" + credentials + "}", null).body(),
                    "$.accessToken");
        }
    }
}
