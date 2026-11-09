package com.example.authservice.controller;

import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-6 AC-6.3 and AC-6.4 (phase 4, T12, D12): a JSON number or boolean where the contract says string is 400
 * MALFORMED_REQUEST, like an array or an object; {@code null} still reaches validation. Background: the Projects
 * fixture, and Ben has invited kit@example.com to Acme as MEMBER.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class StrictJsonTypesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    private Projects projects;
    private ProjectCalls calls;
    private String kitToken;

    @BeforeEach
    void seedProjectsAndInviteKit() throws Exception {
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
        MvcResult invited = calls.as("ben", post(invitations()), "{\"email\":\"kit@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        kitToken = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
    }

    @ParameterizedTest(name = "AC-6.3 {0} {1}: {2} = {3}")
    @CsvSource(delimiter = '|', value = {
            "anonymous | REGISTER | email    | 123",
            "anonymous | REGISTER | fullName | true",
            "anonymous | LOGIN    | password | 12345678",
            "ana       | ORGS     | name     | 42",
            "ana       | PROJECTS | name     | 123",
            "cai       | RENAME   | name     | false",
            "ben       | INVITE   | email    | 7",
            "anonymous | ACCEPT   | password | 12345678"})
    @Tag("AC-6.3")
    void aNumberOrABooleanWhereAStringBelongsIsMalformed(String actor, String request, String field, String value)
            throws Exception {
        // Given
        List<Object> before = state();

        // When
        MvcResult result = perform(actor, request, body(request, field, value));

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", path(request));
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @Test
    @Tag("AC-6.4")
    void aNullForAStringFieldIsStillAValidationError() throws Exception {
        MvcResult result = calls.as("ana", post("/api/orgs"), "{\"name\":null}");

        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", "/api/orgs");
        assertThat(problem.read("$.errors[*].field", List.class)).contains("name");
    }

    private MvcResult perform(String actor, String request, String body) throws Exception {
        MockHttpServletRequestBuilder builder = request.equals("RENAME") ? patch(path(request)) : post(path(request));
        if (actor.equals("anonymous")) {
            return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }
        return calls.as(actor, builder, body);
    }

    /**
     * A valid body for the request, in which {@code field} is the raw JSON value.
     */
    private static String body(String request, String field, String json) {
        String email = field.equals("email") ? json : "\"fay@example.com\"";
        String password = field.equals("password") ? json : "\"Secret123!\"";
        String fullName = field.equals("fullName") ? json : "\"Fay Lowe\"";
        return switch (request) {
            case "REGISTER" -> "{\"email\":" + email + ",\"password\":" + password + ",\"fullName\":" + fullName + "}";
            case "LOGIN" -> "{\"email\":\"ana@example.com\",\"password\":" + password + "}";
            case "ORGS", "PROJECTS", "RENAME" -> "{\"name\":" + json + "}";
            case "INVITE" -> "{\"email\":" + email + ",\"role\":\"MEMBER\"}";
            case "ACCEPT" -> "{\"password\":" + password + ",\"fullName\":" + fullName + "}";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private String path(String request) {
        return switch (request) {
            case "REGISTER" -> "/api/auth/register";
            case "LOGIN" -> "/api/auth/login";
            case "ORGS" -> "/api/orgs";
            case "PROJECTS" -> projects(projects.acme().orgId());
            case "RENAME" -> project(projects.acme().orgId(), projects.checkout());
            case "INVITE" -> invitations();
            case "ACCEPT" -> "/api/auth/invitations/" + kitToken + "/accept";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private String invitations() {
        return "/api/orgs/" + projects.acme().orgId() + "/invitations";
    }

    private List<Object> state() {
        return List.of(userRepository.count(), organizationRepository.count(), orgMembershipRepository.count(),
                projectRepository.count(), invitationRepository.count(),
                projectRepository.findById(projects.checkout()).orElseThrow().getName());
    }
}
