package com.example.authservice.controller;

import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-6 AC-6.1 and AC-6.2 (phase 4, T11, D11 rules T2 and T3): a name made only of invisible characters is blank, and
 * the 100-character name limits count code points, so one emoji is one character. Background: the Projects fixture,
 * and Ben has invited kit@example.com to Acme as MEMBER.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class NameRulesTest {

    private static final String EMOJI = "\uD83D\uDE00";

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
    private ProjectMembershipRepository projectMembershipRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    private Projects projects;
    private ProjectCalls calls;
    private String kitToken;

    @BeforeEach
    void seedProjectsAndInviteKit() throws Exception {
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
        MvcResult invited = calls.as("ben", post("/api/orgs/" + projects.acme().orgId() + "/invitations"),
                "{\"email\":\"kit@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        kitToken = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
    }

    @ParameterizedTest(name = "AC-6.1 {0} {1}: {2} = {3}")
    @CsvSource(delimiter = '|', value = {
            "ana       | ORGS     | name     | 00A0",
            "ana       | PROJECTS | name     | 200B",
            "cai       | RENAME   | name     | FEFF",
            "anonymous | REGISTER | fullName | 2060 00A0",
            "anonymous | ACCEPT   | fullName | 202F 0020 200D"})
    @Tag("AC-6.1")
    void aNameMadeOnlyOfInvisibleCharactersIsBlank(String actor, String request, String field, String codePoints)
            throws Exception {
        // Given
        String value = Arrays.stream(codePoints.split(" "))
                .map(hex -> Character.toString(Integer.parseInt(hex, 16)))
                .collect(Collectors.joining());
        State before = state();

        // When
        MvcResult result = perform(actor, request, field, value);

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path(request));
        assertThat(problem.read("$.errors[*].field", List.class)).containsExactly(field);
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-6.2 {0} {1}: {2} = {3} gives {4}")
    @CsvSource(delimiter = '|', value = {
            "ana       | ORGS     | name     | 100 emoji             | 201",
            "ana       | ORGS     | name     | 101 emoji             | 400",
            "ana       | PROJECTS | name     | 100 emoji             | 201",
            "ana       | PROJECTS | name     | one space, then 100 emoji | 400",
            "anonymous | REGISTER | fullName | 100 e-acute           | 201",
            "anonymous | REGISTER | fullName | 101 e-acute           | 400"})
    @Tag("AC-6.2")
    void nameLimitsCountCodePoints(String actor, String request, String field, String value, int status)
            throws Exception {
        // Given
        String name = switch (value) {
            case "100 emoji" -> EMOJI.repeat(100);
            case "101 emoji" -> EMOJI.repeat(101);
            case "one space, then 100 emoji" -> " " + EMOJI.repeat(100);
            case "100 e-acute" -> "\u00E9".repeat(100);
            case "101 e-acute" -> "\u00E9".repeat(101);
            default -> throw new IllegalArgumentException(value);
        };

        // When
        MvcResult result = perform(actor, request, field, name);

        // Then
        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            return;
        }
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path(request));
        assertThat(problem.read("$.errors[*].field", List.class)).containsExactly(field);
    }

    private MvcResult perform(String actor, String request, String field, String value) throws Exception {
        String body = body(request, field, JsonPath.parse(List.of(value)).jsonString().replaceAll("^\\[|]$", ""));
        MockHttpServletRequestBuilder builder = request.equals("RENAME") ? patch(path(request)) : post(path(request));
        if (actor.equals("anonymous")) {
            return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }
        return calls.as(actor, builder, body);
    }

    /**
     * A valid body for the request, in which {@code field} is the given JSON value.
     */
    private static String body(String request, String field, String jsonValue) {
        return switch (request) {
            case "REGISTER" -> "{\"email\":\"fay@example.com\",\"password\":\"Secret123!\",\"fullName\":" + jsonValue
                    + "}";
            case "ORGS", "PROJECTS", "RENAME" -> "{\"" + field + "\":" + jsonValue + "}";
            case "ACCEPT" -> "{\"password\":\"Secret123!\",\"fullName\":" + jsonValue + "}";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private String path(String request) {
        return switch (request) {
            case "REGISTER" -> "/api/auth/register";
            case "ORGS" -> "/api/orgs";
            case "PROJECTS" -> projects(projects.acme().orgId());
            case "RENAME" -> project(projects.acme().orgId(), projects.checkout());
            case "ACCEPT" -> "/api/auth/invitations/" + kitToken + "/accept";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private State state() {
        return new State(userRepository.count(), organizationRepository.count(), orgMembershipRepository.count(),
                projectRepository.count(), projectMembershipRepository.count(), invitationRepository.count(),
                projectRepository.findById(projects.checkout()).orElseThrow().getName());
    }

    private record State(long users, long organizations, long orgMemberships, long projects, long projectMemberships,
                         long invitations, String checkoutName) {
    }
}
