package com.example.authservice.controller;

import com.example.authservice.model.User;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-7: the access check for other services, {@code GET /internal/projects/{projectId}/access} (D9, contract C5),
 * called directly with the end user's bearer token.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class ProjectAccessCheckTest {

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
    @Tag("AC-7.1")
    @DisplayName("AC-7.1 Ana: the body is exactly projectId, orgId, role ADMIN and archived false")
    void theBodyIsExactlyTheC5Fields() throws Exception {
        // When
        MvcResult result = mockMvc.perform(accessCheck(projects.checkout().toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ana@example.com")))).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).isEqualTo(Map.of(
                "projectId", projects.checkout().toString(),
                "orgId", projects.acme().orgId().toString(),
                "role", "ADMIN",
                "archived", false));
    }

    @ParameterizedTest(name = "AC-7.1 {0}: {1}")
    @CsvSource({
            "ben, ADMIN", "cai, ADMIN", "gus, MEMBER", "hal, TESTER", "dan, READER",
            "ivy, FORBIDDEN", "eve, FORBIDDEN"
    })
    @Tag("AC-7.1")
    void theAccessCheckReturnsTheCallersEffectiveRole(String user, String outcome) throws Exception {
        // Given
        String path = path(projects.checkout().toString());

        // When
        MvcResult result = mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, user + "@example.com")))).andReturn();

        // Then
        if (outcome.equals("FORBIDDEN")) {
            assertProblem(result, 403, "FORBIDDEN", path);
            return;
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("projectId", "orgId", "role", "archived");
        assertThat(body.get("role")).isEqualTo(outcome);
        assertThat(body.get("archived")).isEqualTo(false);
    }

    @ParameterizedTest(name = "AC-7.2 id {0}: {1} {2}")
    @CsvSource({"UNKNOWN, 403, FORBIDDEN", "PORTAL, 403, FORBIDDEN", "not-a-uuid, 400, MALFORMED_REQUEST"})
    @Tag("AC-7.2")
    void anUnknownOrMalformedProjectIdRevealsNothing(String id, int status, String code) throws Exception {
        // Given
        String projectId = switch (id) {
            case "UNKNOWN" -> AcmeFixture.UNKNOWN_ID.toString();
            case "PORTAL" -> projects.portal().toString();
            default -> id;
        };
        String path = path(projectId);

        // When
        MvcResult result = mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ana@example.com")))).andReturn();

        // Then
        assertProblem(result, status, code, path);
    }

    @ParameterizedTest(name = "AC-7.3 authorization: {0}")
    @ValueSource(strings = {"NONE", "EXPIRED", "OTHER_SECRET"})
    @Tag("AC-7.3")
    void theAccessCheckNeedsTheEndUsersValidToken(String authorization) throws Exception {
        // Given
        String path = path(projects.checkout().toString());
        User hal = projects.hal();
        MockHttpServletRequestBuilder request = get(path);
        switch (authorization) {
            case "EXPIRED" -> request.header(HttpHeaders.AUTHORIZATION,
                    bearer(TestJwt.expired(hal.getId(), hal.getEmail())));
            case "OTHER_SECRET" -> request.header(HttpHeaders.AUTHORIZATION,
                    bearer(TestJwt.signedWithAnotherSecret(hal.getId(), hal.getEmail())));
            default -> {
            }
        }

        // When
        MvcResult result = mockMvc.perform(request).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", path);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    @Tag("AC-7.4")
    @DisplayName("AC-7.4 An archived project is reported as archived, with the role")
    void anArchivedProjectIsReportedAsArchivedWithTheRole() throws Exception {
        // Given Cai has archived Checkout
        MvcResult archived = mockMvc.perform(post("/api/orgs/" + projects.acme().orgId() + "/projects/"
                + projects.checkout() + "/archive")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "cai@example.com")))).andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);

        // When
        MvcResult result = mockMvc.perform(accessCheck(projects.checkout().toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "hal@example.com")))).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body.get("role")).isEqualTo("TESTER");
        assertThat(body.get("archived")).isEqualTo(true);
    }

    @Test
    @Tag("AC-7.5")
    @DisplayName("AC-7.5 A removal is visible to the access check on the next request")
    void aRemovalIsVisibleToTheAccessCheckOnTheNextRequest() throws Exception {
        // Given
        String token = tokenOf(mockMvc, "hal@example.com");
        String path = path(projects.checkout().toString());
        MvcResult before = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();
        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(before.getResponse().getContentAsString(), "$.role")).isEqualTo("TESTER");
        MvcResult removed = mockMvc.perform(delete("/api/orgs/" + projects.acme().orgId() + "/projects/"
                + projects.checkout() + "/members/" + projects.hal().getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "cai@example.com")))).andReturn();
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);

        // When
        MvcResult after = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();

        // Then
        assertProblem(after, 403, "FORBIDDEN", path);
    }

    private static MockHttpServletRequestBuilder accessCheck(String projectId) {
        return get(path(projectId));
    }

    private static String path(String projectId) {
        return "/internal/projects/" + projectId + "/access";
    }
}
