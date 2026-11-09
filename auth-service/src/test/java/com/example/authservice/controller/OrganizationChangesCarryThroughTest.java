package com.example.authservice.controller;

import com.example.authservice.model.User;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-6 (T15, E38, E39): organization membership and role changes carry through to projects, through the phase-1
 * organization operations.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class OrganizationChangesCarryThroughTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private ProjectMembershipRepository projectMembershipRepository;

    private Projects projects;
    private ProjectCalls calls;
    private String acme;
    private String checkout;

    @BeforeEach
    void seedProjects() {
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
        acme = "/api/orgs/" + projects.acme().orgId();
        checkout = project(projects.acme().orgId(), projects.checkout());
    }

    @ParameterizedTest(name = "AC-6.1 {0} removes {1} from Acme")
    @CsvSource({"ben, hal", "gus, gus"})
    @Tag("AC-6.1")
    void leavingOrBeingRemovedFromTheOrganizationRemovesProjectMemberships(String actor, String person)
            throws Exception {
        // Given
        User user = person.equals("hal") ? projects.hal() : projects.gus();
        MvcResult removed = calls.as(actor, delete(acme + "/members/" + user.getId()));
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);

        // When
        MvcResult result = calls.as("cai", get(checkout + "/members"));

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$[*].email"))
                .doesNotContain(user.getEmail());
        assertThat(projectMembershipRepository.findAll())
                .noneMatch(membership -> membership.getUser().getId().equals(user.getId()));
    }

    @Test
    @Tag("AC-6.2")
    @DisplayName("AC-6.2 Someone who rejoins the organization starts without project access")
    void someoneWhoRejoinsStartsWithoutProjectAccess() throws Exception {
        // Given
        assertThat(calls.as("ben", delete(acme + "/members/" + projects.hal().getId())).getResponse().getStatus())
                .isEqualTo(204);
        MvcResult invited = calls.as("ben", post(acme + "/invitations"),
                "{\"email\":\"hal@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        String token = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
        MvcResult accepted = calls.as("hal", post("/api/auth/invitations/" + token + "/accept"));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);

        // When
        MvcResult result = calls.as("hal", get(projects(projects.acme().orgId())));

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3 Becoming an organization reader lowers project roles to reader")
    void becomingAnOrganizationReaderLowersProjectRoles() throws Exception {
        // Given
        MvcResult changed = calls.as("ana", patch(acme + "/members/" + projects.acme().cai().getId()),
                "{\"role\":\"READER\"}");
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);

        // When
        MvcResult result = calls.as("cai", patch(checkout), "{\"name\":\"Checkout Web\"}");

        // Then
        assertProblem(result, 403, "FORBIDDEN", checkout);
        assertThat(projectRoles()).containsEntry("cai@example.com", "READER");
    }

    @ParameterizedTest(name = "AC-6.4 {0} becomes Acme {1}: project role {2}, effective {3}")
    @CsvSource({"gus, ADMIN, MEMBER, ADMIN", "dan, MEMBER, READER, READER", "hal, READER, READER, READER"})
    @Tag("AC-6.4")
    void otherOrganizationRoleChangesKeepExplicitProjectRoles(String person, String orgRole, String projectRole,
                                                              String effectiveRole) throws Exception {
        // Given
        User user = switch (person) {
            case "gus" -> projects.gus();
            case "dan" -> projects.acme().dan();
            default -> projects.hal();
        };
        MvcResult changed = calls.as("ana", patch(acme + "/members/" + user.getId()),
                "{\"role\":\"" + orgRole + "\"}");
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);

        // When
        Map<String, String> roles = projectRoles();

        // Then
        assertThat(roles).containsEntry(user.getEmail(), projectRole);
        MvcResult view = calls.as(person, get(checkout));
        assertThat(JsonPath.<String>read(view.getResponse().getContentAsString(), "$.myRole")).isEqualTo(effectiveRole);
    }

    @Test
    @Tag("AC-6.5")
    @DisplayName("AC-6.5 Becoming an organization admin gives admin rights on every project")
    void becomingAnOrganizationAdminGivesAdminRightsOnEveryProject() throws Exception {
        // Given
        calls.as("ana", patch(acme + "/members/" + projects.acme().dan().getId()), "{\"role\":\"ADMIN\"}");

        // When
        MvcResult result = calls.as("dan", get(project(projects.acme().orgId(), projects.billing())));

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.myRole")).isEqualTo("ADMIN");
    }

    @Test
    @Tag("AC-6.6")
    @DisplayName("AC-6.6 Losing organization admin removes implicit project admin rights")
    void losingOrganizationAdminRemovesImplicitProjectAdminRights() throws Exception {
        // Given
        calls.as("ana", patch(acme + "/members/" + projects.acme().ben().getId()), "{\"role\":\"MEMBER\"}");
        String billing = project(projects.acme().orgId(), projects.billing());

        // When
        MvcResult result = calls.as("ben", get(billing));

        // Then
        assertProblem(result, 403, "FORBIDDEN", billing);
        assertThat(calls.as("ben", get(projects(projects.acme().orgId()))).getResponse().getContentAsString())
                .isEqualTo("[]");
    }

    private Map<String, String> projectRoles() throws Exception {
        String json = calls.as("ana", get(checkout + "/members")).getResponse().getContentAsString();
        List<Map<String, Object>> members = JsonPath.read(json, "$");
        return members.stream().collect(Collectors.toMap(
                member -> (String) member.get("email"), member -> (String) member.get("role")));
    }
}
