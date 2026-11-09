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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-5 (phase 4, T1, E59): text PostgreSQL can't store (a control character or a lone surrogate in the stored value of
 * a name or email) is a 400 VALIDATION_FAILED before it reaches the database. Background: the Projects fixture, and Ben
 * has invited {@value #INVITEE} to Acme as MEMBER. The spec's Background names gus@example.com, but Gus is already an
 * Acme member in the Projects fixture, so an unregistered address takes his place (reported in the T1 handoff).
 * Values are JSON escapes in the request body, which Jackson decodes into the code points.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
@ExtendWith(OutputCaptureExtension.class)
class UnstorableTextTest {

    private static final String INVITEE = "kit@example.com";

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
    private String inviteeToken;

    @BeforeEach
    void seedProjectsAndInvite() throws Exception {
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
        MvcResult invited = calls.as("ben", post(invitations()), "{\"email\":\"" + INVITEE + "\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        inviteeToken = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
    }

    @ParameterizedTest(name = "AC-5.1 {0} {1}: {2} = {3}")
    @CsvSource(delimiter = '|', value = {
            "anonymous | REGISTER | fullName | Fay\\u0000Lowe",
            "anonymous | REGISTER | fullName | Fay \\uD800",
            "anonymous | REGISTER | email    | fay\\uDC00@example.com",
            "anonymous | LOGIN    | email    | ana\\uD800@example.com",
            "ana       | ORGS     | name     | Org\\u0000QA",
            "ana       | ORGS     | name     | \\tOrg QA",
            "ana       | ORGS     | name     | Org QA\\u0085",
            "ana       | PROJECTS | name     | Mobile\\u0007App",
            "ana       | PROJECTS | name     | \\uDC00",
            "cai       | RENAME   | name     | Check\\u007Fout",
            "ben       | INVITE   | email    | fay\\uD800@example.com",
            "anonymous | ACCEPT   | fullName | Gus\\u0000Hale"})
    @Tag("AC-5.1")
    @DisplayName("AC-5.1 A control character or a lone surrogate in a stored name or email is rejected")
    void unstorableTextIsAValidationError(String actor, String request, String field, String jsonValue,
                                          CapturedOutput output) throws Exception {
        // Given
        String body = body(request, field, jsonValue);
        String decoded = JsonPath.read("{\"v\":\"" + jsonValue + "\"}", "$.v");
        State before = state();
        int logStart = output.getAll().length();

        // When
        MvcResult result = perform(actor, builder(request), body);

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path(request));
        assertThat(problem.read("$.errors[*].field", List.class)).containsExactly(field);
        String responseBody = result.getResponse().getContentAsString();
        String log = output.getAll().substring(logStart);
        for (String form : List.of(decoded, jsonValue, printed(decoded))) {
            assertThat(responseBody).doesNotContain(form);
            assertThat(log).doesNotContain(form);
        }
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-5.2 {0} gives {1}")
    @CsvSource(delimiter = '|', value = {
            "\\u0000Checkout | 409 |",
            "Mobile App\\n   | 201 | Mobile App"})
    @Tag("AC-5.2")
    @DisplayName("AC-5.2 Characters that trimming removes from a project name don't count")
    void charactersThatTrimmingRemovesDontCount(String jsonValue, int status, String storedName) throws Exception {
        // When
        MvcResult result = calls.as("ana", post(projects(projects.acme().orgId())), "{\"name\":\"" + jsonValue + "\"}");

        // Then
        if (status == 409) {
            assertProblem(result, 409, "PROJECT_NAME_TAKEN", projects(projects.acme().orgId()));
            assertThat(projectRepository.findByOrganizationId(projects.acme().orgId()))
                    .extracting(p -> p.getName()).containsExactlyInAnyOrder("Billing", "Checkout");
            return;
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.name")).isEqualTo(storedName);
    }

    @ParameterizedTest(name = "AC-5.4 {0}")
    @CsvSource(delimiter = '|', value = {
            "Caf\\u00E9 \\u00DCn\\u00EFc\\u00F6d\\u00E9",
            "\\u65E5\\u672C\\u8A9E\\u30C1\\u30FC\\u30E0",
            "\\u00C9quipe \\uD83D\\uDE00",
            "e\\u0301clair"})
    @Tag("AC-5.4")
    @DisplayName("AC-5.4 Ordinary non-ASCII names are accepted and stored exactly")
    void ordinaryNonAsciiNamesAreStoredExactly(String jsonValue) throws Exception {
        // Given
        String decoded = JsonPath.read("{\"v\":\"" + jsonValue + "\"}", "$.v");

        // When
        MvcResult result = calls.as("ana", post("/api/orgs"), "{\"name\":\"" + jsonValue + "\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
        String stored = organizationRepository.findById(id).orElseThrow().getName();
        assertThat(stored.codePoints().toArray()).isEqualTo(decoded.codePoints().toArray());
    }

    private MvcResult perform(String actor, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (actor.equals("anonymous")) {
            return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }
        return calls.as(actor, request, body);
    }

    /**
     * A valid body for the request, in which {@code field} is the given JSON string value.
     */
    private static String body(String request, String field, String jsonValue) {
        String value = "\"" + jsonValue + "\"";
        return switch (request) {
            case "REGISTER" -> "{\"email\":" + (field.equals("email") ? value : "\"fay@example.com\"")
                    + ",\"password\":\"Secret123!\",\"fullName\":" + (field.equals("fullName") ? value : "\"Fay Lowe\"")
                    + "}";
            case "LOGIN" -> "{\"email\":" + value + ",\"password\":\"Secret123!\"}";
            case "ORGS", "PROJECTS", "RENAME" -> "{\"name\":" + value + "}";
            case "INVITE" -> "{\"email\":" + value + ",\"role\":\"MEMBER\"}";
            case "ACCEPT" -> "{\"password\":\"Secret123!\",\"fullName\":" + value + "}";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private MockHttpServletRequestBuilder builder(String request) {
        return request.equals("RENAME") ? patch(path(request)) : post(path(request));
    }

    private String path(String request) {
        return switch (request) {
            case "REGISTER" -> "/api/auth/register";
            case "LOGIN" -> "/api/auth/login";
            case "ORGS" -> "/api/orgs";
            case "PROJECTS" -> projects(projects.acme().orgId());
            case "RENAME" -> project(projects.acme().orgId(), projects.checkout());
            case "INVITE" -> invitations();
            case "ACCEPT" -> "/api/auth/invitations/" + inviteeToken + "/accept";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private String invitations() {
        return "/api/orgs/" + projects.acme().orgId() + "/invitations";
    }

    /**
     * The value as an encoder that can't represent a control character or a lone surrogate would print it.
     */
    private static String printed(String decoded) {
        StringBuilder printed = new StringBuilder();
        decoded.chars().forEach(c -> printed.append(Character.isISOControl(c) || Character.isSurrogate((char) c)
                ? '?' : (char) c));
        return printed.toString();
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
