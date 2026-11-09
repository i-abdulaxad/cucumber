package com.example.authservice.qa;

import com.example.authservice.aop.RequestLog;
import com.example.authservice.model.User;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.authservice.qa.QaSupport.concurrently;
import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ProjectCalls.member;
import static com.example.authservice.support.ProjectCalls.members;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa checks for US-9 (D17) over the full projects flow, with {@code com.example.authservice} at DEBUG and with
 * devtools' {@code spring.mvc.log-resolved-exception=true} default applied the way devtools applies it under
 * {@code bootRun} (lowest precedence, AC-9.13):
 * <ul>
 *   <li>every log entry written while a request is handled carries that request's ID and the caller's user ID
 *       (AC-9.6, AC-9.7), also when requests run at the same time on different threads;</li>
 *   <li>no line contains an email, a full name, a password or hash, a JWT or {@code Authorization} value, or a
 *       project name (AC-9.9, AC-9.13, NFR "Audit": project INFO lines contain ids only);</li>
 *   <li>the MDC is empty after each request (AC-9.8).</li>
 * </ul>
 * This class has its own context and shares the PostgreSQL test database.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
@ContextConfiguration(initializers = QaProjectLoggingTest.DevToolsDefaults.class)
@ExtendWith(OutputCaptureExtension.class)
class QaProjectLoggingTest {

    private static final String LOGGER = "com.example.authservice";
    private static final Pattern REQUEST_ID = Pattern.compile("requestId=([^ \\]]+)");
    private static final Pattern USER_ID = Pattern.compile("userId=([^ \\]]+)");
    private static final List<String> PERSONAL_DATA = List.of(
            "ana@example.com", "ben@example.com", "cai@example.com", "dan@example.com", "eve@example.com",
            "gus@example.com", "hal@example.com", "ivy@example.com",
            "Ana Lima", "Ben Ode", "Cai Wu", "Dan Roe", "Eve Park", "Gus Hale", "Hal Ito", "Ivy Moss",
            AcmeFixture.PASSWORD, "$2a$", "$2b$", "$2y$", "Bearer ", "eyJ",
            "Mobile App QA", "Checkout QA Web", "Leaky ivy@example.com");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private LoggingSystem loggingSystem;

    private Projects projects;
    private UUID acme;

    @BeforeEach
    void seed() {
        projects = fixture.seed();
        acme = projects.acme().orgId();
        loggingSystem.setLogLevel(LOGGER, LogLevel.DEBUG);
    }

    @AfterEach
    void restoreLevel() {
        loggingSystem.setLogLevel(LOGGER, null);
    }

    @Test
    @Tag("AC-9.6")
    @Tag("AC-9.7")
    @Tag("AC-9.8")
    @Tag("AC-9.9")
    @Tag("AC-9.13")
    @DisplayName("qa US-9: the full projects flow is correlated on every line and logs no personal data or secrets")
    void theFullProjectsFlowIsCorrelatedAndLogsNoPersonalData(CapturedOutput output) throws Exception {
        // Given
        List<String> secrets = new ArrayList<>();
        UUID checkout = projects.checkout();
        String checkoutPath = project(acme, checkout);
        String access = "/internal/projects/" + checkout + "/access";
        int start = output.getAll().length();

        // When: login, then every project operation, with successes and every business error
        MvcResult login = send(null, "qa-p-01", post("/api/auth/login"), loginBody("ana@example.com",
                AcmeFixture.PASSWORD), 200, output, secrets);
        secrets.add(JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken"));
        User ana = projects.acme().ana();
        User cai = projects.acme().cai();
        send(ana, "qa-p-02", post(projects(acme)), "{\"name\":\"Mobile App QA\"}", 201, output, secrets);
        send(ana, "qa-p-03", post(projects(acme)), "{\"name\":\"mobile app qa\"}", 409, output, secrets);
        send(projects.gus(), "qa-p-04", get(projects(acme)), null, 200, output, secrets);
        send(projects.gus(), "qa-p-05", get(checkoutPath), null, 200, output, secrets);
        send(cai, "qa-p-06", patch(checkoutPath), "{\"name\":\"Checkout QA Web\"}", 200, output, secrets);
        send(cai, "qa-p-07", post(members(acme, checkout)), addBody(projects.ivy(), "TESTER"), 201, output, secrets);
        send(cai, "qa-p-08", post(members(acme, checkout)), addBody(projects.acme().eve(), "READER"), 422, output,
                secrets);
        send(cai, "qa-p-09", post(members(acme, checkout)), addBody(projects.gus(), "READER"), 409, output, secrets);
        send(cai, "qa-p-10", patch(member(acme, checkout, projects.ivy().getId())), "{\"role\":\"MEMBER\"}", 200,
                output, secrets);
        send(projects.acme().dan(), "qa-p-11", patch(member(acme, checkout, projects.ivy().getId())),
                "{\"role\":\"READER\"}", 403, output, secrets);
        send(projects.hal(), "qa-p-12", get(access), null, 200, output, secrets);
        send(cai, "qa-p-13", post(checkoutPath + "/archive"), null, 200, output, secrets);
        send(cai, "qa-p-14", patch(member(acme, checkout, projects.ivy().getId())), "{\"role\":\"READER\"}", 409,
                output, secrets);
        send(cai, "qa-p-15", post(checkoutPath + "/restore"), null, 200, output, secrets);
        send(projects.ivy(), "qa-p-16", delete(member(acme, checkout, projects.ivy().getId())), null, 204, output,
                secrets);
        send(ana, "qa-p-17", delete(member(acme, checkout, projects.hal().getId())), null, 204, output, secrets);
        send(projects.gus(), "qa-p-18", patch(member(acme, checkout, projects.gus().getId())),
                "{\"role\":\"READER\"}", 422, output, secrets);
        send(ana, "qa-p-19", post(members(acme, checkout)), "{\"userId\":\"not-a-uuid\",\"role\":\"READER\"}", 400,
                output, secrets);
        send(ana, "qa-p-20", post(projects(acme)), "{\"name\":\"Leaky ivy@example.com" + "x".repeat(90) + "\"}", 400,
                output, secrets);
        send(projects.acme().eve(), "qa-p-21", get(checkoutPath), null, 403, output, secrets);
        send(null, "qa-p-22", get(access), null, 401, output, secrets);
        send(ana, "qa-p-23", post(members(acme, projects.billing())), addBody(projects.acme().dan(), "ADMIN"), 422,
                output, secrets);
        send(ana, "qa-p-24", post(project(acme, projects.billing()) + "/archive"), null, 200, output, secrets);
        send(ana, "qa-p-25", delete("/api/orgs/" + acme + "/members/" + projects.gus().getId()), null, 204, output,
                secrets);
        send(ana, "qa-p-26", patch("/api/orgs/" + acme + "/members/" + cai.getId()), "{\"role\":\"READER\"}", 200,
                output, secrets);

        // Then: the whole captured log holds none of the values
        String log = output.getAll().substring(start);
        String lowerLog = log.toLowerCase(Locale.ROOT);
        for (String value : PERSONAL_DATA) {
            assertThat(lowerLog).as("the log contains %s", value).doesNotContain(value.toLowerCase(Locale.ROOT));
        }
        for (String secret : secrets) {
            assertThat(log).as("the log contains a token").doesNotContain(secret);
        }
        assertThat(MDC.getCopyOfContextMap() == null ? Map.of() : MDC.getCopyOfContextMap())
                .doesNotContainKeys("requestId", "userId");
    }

    @Test
    @Tag("AC-9.7")
    @DisplayName("qa AC-9.7: the access check without a valid X-Request-Id gets one generated UUID on every line")
    void theAccessCheckGetsAGeneratedRequestId(CapturedOutput output) throws Exception {
        // Given
        String access = "/internal/projects/" + projects.checkout() + "/access";
        int start = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(get(access).header("X-Request-Id", "bad id qa-77")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(projects.hal())))).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> entries = RequestLog.since(output, start).entries();
        assertThat(entries).isNotEmpty();
        List<String> ids = entries.stream().map(line -> group(REQUEST_ID, line)).distinct().toList();
        assertThat(ids).singleElement().satisfies(id -> assertThat(UUID.fromString(id)).isNotNull());
        assertThat(output.getAll().substring(start)).doesNotContain("bad id qa-77");
        assertThat(entries).allSatisfy(line ->
                assertThat(group(USER_ID, line)).isEqualTo(projects.hal().getId().toString()));
    }

    @RepeatedTest(value = 3, name = "qa AC-9.6: simultaneous requests keep their own request and user IDs (run {currentRepetition})")
    @Tag("AC-9.6")
    @Tag("AC-9.8")
    void simultaneousRequestsKeepTheirOwnIds(CapturedOutput output) throws Exception {
        // Given
        List<User> users = List.of(projects.acme().ana(), projects.acme().cai(), projects.gus(), projects.hal(),
                projects.acme().dan(), projects.acme().ben());
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            User user = users.get(i % users.size());
            String requestId = "qa-par-" + i;
            String path = i % 2 == 0 ? "/internal/projects/" + projects.checkout() + "/access"
                    : project(acme, projects.checkout());
            calls.add(() -> mockMvc.perform(get(path).header("X-Request-Id", requestId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token(user)))).andReturn());
        }
        int start = output.getAll().length();

        // When
        List<MvcResult> results = concurrently(calls);

        // Then
        assertThat(QaSupport.statuses(results)).containsOnly(200);
        List<String> correlated = RequestLog.since(output, start).entries().stream()
                .filter(line -> line.contains("requestId=qa-par-")).toList();
        assertThat(correlated).hasSizeGreaterThanOrEqualTo(12);
        for (String line : correlated) {
            int index = Integer.parseInt(group(REQUEST_ID, line).substring("qa-par-".length()));
            assertThat(group(USER_ID, line)).as(line).isEqualTo(users.get(index % users.size()).getId().toString());
        }
    }

    private MvcResult send(User actor, String requestId, MockHttpServletRequestBuilder request, String body,
                           int status, CapturedOutput output, List<String> secrets) throws Exception {
        request.header("X-Request-Id", requestId);
        if (actor != null) {
            String token = token(actor);
            secrets.add(token);
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        int start = output.getAll().length();
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(requestId).isEqualTo(status);
        List<String> entries = RequestLog.since(output, start).entries();
        if (status != 400 && status != 401 && status != 403) {
            // a service use case ran, so the aspect wrote at least its DEBUG lines: the checks below aren't vacuous
            assertThat(entries).as("%s writes at least one log entry at DEBUG", requestId).isNotEmpty();
        }
        String expectedUser = actor == null ? "-" : actor.getId().toString();
        for (String line : entries) {
            assertThat(group(REQUEST_ID, line)).as("request ID of: %s", line).isEqualTo(requestId);
            assertThat(group(USER_ID, line)).as("user ID of: %s", line).isEqualTo(expectedUser);
        }
        assertThat(MDC.get("requestId")).as("MDC after %s", requestId).isNull();
        assertThat(MDC.get("userId")).as("MDC after %s", requestId).isNull();
        return result;
    }

    private static String token(User user) {
        return TestJwt.valid(user.getId(), user.getEmail());
    }

    private static String addBody(User user, String role) {
        return "{\"userId\":\"" + user.getId() + "\",\"role\":\"" + role + "\"}";
    }

    private static String group(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : "<none>";
    }

    /**
     * devtools' web default, added the way devtools adds it: lowest precedence, below application.yaml.
     */
    static class DevToolsDefaults implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addLast(
                    new MapPropertySource("devtools", Map.of("spring.mvc.log-resolved-exception", "true")));
        }
    }
}
