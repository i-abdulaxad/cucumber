package com.example.authservice.controller;

import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.example.authservice.support.TestServiceKey;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Locale;
import java.util.Map;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * report-service spec US-8 (C7, D5, AE1–AE3): {@code GET /internal/service/projects/{projectId}} answers a service
 * that sends the right {@code X-Service-Key}, and nobody else. This context runs with the TEST-ONLY key set; the
 * unset case is {@code InternalServiceKeyUnsetTest}.
 */
@AuthServiceIntegrationTest
@TestPropertySource(properties = "app.internal-service-key=" + TestServiceKey.KEY)
@Import(ProjectFixture.class)
@ExtendWith(OutputCaptureExtension.class)
class InternalProjectStatusTest {

    static final String SERVICE_KEY_HEADER = "X-Service-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    private Projects projects;

    @BeforeEach
    void seedProjects() {
        projects = fixture.seed();
    }

    @Test
    @Tag("AC-8.1")
    @DisplayName("AC-8.1 With the key: exactly projectId, orgId and archived false; after Ana archives, archived true")
    void aServiceWithTheKeyReadsTheProjectStatus() throws Exception {
        // When
        MvcResult result = mockMvc.perform(status(projects.checkout().toString())
                .header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(body(result)).isEqualTo(Map.of(
                "projectId", projects.checkout().toString(),
                "orgId", projects.acme().orgId().toString(),
                "archived", false));

        // And after Ana archives Checkout
        String ana = tokenOf(mockMvc, "ana@example.com");
        assertThat(mockMvc.perform(post("/api/orgs/" + projects.acme().orgId() + "/projects/" + projects.checkout()
                + "/archive").header(HttpHeaders.AUTHORIZATION, bearer(ana))).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        MvcResult archived = mockMvc.perform(status(projects.checkout().toString())
                .header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn();
        assertThat(body(archived)).isEqualTo(Map.of(
                "projectId", projects.checkout().toString(),
                "orgId", projects.acme().orgId().toString(),
                "archived", true));
    }

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 No X-Service-Key: 401 UNAUTHENTICATED")
    void withoutAKeyThereIsNoAnswer() throws Exception {
        String path = path(projects.checkout().toString());

        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED", path);
    }

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 Another 32-byte key: 401 UNAUTHENTICATED")
    void withAnotherKeyThereIsNoAnswer() throws Exception {
        String path = path(projects.checkout().toString());

        MvcResult result = mockMvc.perform(get(path).header(SERVICE_KEY_HEADER, TestServiceKey.OTHER_KEY)).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED", path);
    }

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 Only Ana's bearer token: 401 UNAUTHENTICATED (a user is not a service)")
    void aUserTokenIsNotAServiceCredential() throws Exception {
        String path = path(projects.checkout().toString());
        String ana = tokenOf(mockMvc, "ana@example.com");

        MvcResult result = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(ana))).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED", path);
    }

    @Test
    @Tag("AC-8.3")
    @DisplayName("AC-8.3 An unknown project id: 404 NOT_FOUND")
    void anUnknownProjectIsNotFound() throws Exception {
        String path = path("00000000-0000-4000-8000-000000000000");

        MvcResult result = mockMvc.perform(get(path).header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn();

        assertProblem(result, 404, "NOT_FOUND", path);
    }

    @Test
    @Tag("AC-8.3")
    @DisplayName("AC-8.3 An upper-case id identifies the same project: 200 with projectId in lower case (BD-1)")
    void anUpperCaseIdIdentifiesTheSameProject() throws Exception {
        String upperCase = projects.checkout().toString().toUpperCase(Locale.ROOT);

        MvcResult result = mockMvc.perform(status(upperCase).header(SERVICE_KEY_HEADER, TestServiceKey.KEY))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).isEqualTo(Map.of(
                "projectId", projects.checkout().toString(),
                "orgId", projects.acme().orgId().toString(),
                "archived", false));
    }

    @Test
    @DisplayName("C7 contract: an id that isn't a canonical UUID is 400 MALFORMED_REQUEST")
    void aMalformedProjectIdIsRejected() throws Exception {
        String path = path("1-1-1-1-1");

        MvcResult result = mockMvc.perform(get(path).header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn();

        assertProblem(result, 400, "MALFORMED_REQUEST", path);
    }

    @Test
    @Tag("AC-8.4")
    @DisplayName("AC-8.4 C5 with X-Service-Key and no JWT is still 401")
    void theServiceKeyIsNoCredentialForTheAccessCheck() throws Exception {
        String path = "/internal/projects/" + projects.checkout() + "/access";

        MvcResult result = mockMvc.perform(get(path).header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED", path);
    }

    @Test
    @Tag("AC-8.5")
    @DisplayName("AC-8.5 No log line, at DEBUG, contains a key or the X-Service-Key header")
    void neitherTheKeyNorTheHeaderIsLogged(CapturedOutput output) throws Exception {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        LogLevel rootFound = configuredLevel(logging, LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel serviceFound = configuredLevel(logging, "com.example.authservice");
        int logStart = output.getAll().length();
        try {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
            logging.setLogLevel("com.example.authservice", LogLevel.DEBUG);

            // When: an answered call, a wrong key, and an unknown project
            assertThat(mockMvc.perform(status(projects.checkout().toString())
                    .header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(mockMvc.perform(status(projects.checkout().toString())
                    .header(SERVICE_KEY_HEADER, TestServiceKey.OTHER_KEY)).andReturn().getResponse().getStatus())
                    .isEqualTo(401);
            assertThat(mockMvc.perform(status("00000000-0000-4000-8000-000000000000")
                    .header(SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn().getResponse().getStatus())
                    .isEqualTo(404);
        } finally {
            // Log levels are JVM-global: put back what the test found
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootFound);
            logging.setLogLevel("com.example.authservice", serviceFound);
        }

        // Then
        String log = output.getAll().substring(logStart);
        assertThat(log).contains(" DEBUG ")
                .doesNotContain(TestServiceKey.KEY, TestServiceKey.OTHER_KEY, SERVICE_KEY_HEADER);
    }

    private static String path(String projectId) {
        return "/internal/service/projects/" + projectId;
    }

    private static MockHttpServletRequestBuilder status(String projectId) {
        return get(path(projectId));
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$");
    }

    private static LogLevel configuredLevel(LoggingSystem logging, String logger) {
        LoggerConfiguration configuration = logging.getLoggerConfiguration(logger);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }
}
