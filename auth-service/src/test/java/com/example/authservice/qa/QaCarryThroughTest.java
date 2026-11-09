package com.example.authservice.qa;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.ProjectRole;
import com.example.authservice.model.OrgMembership;
import com.example.authservice.model.Organization;
import com.example.authservice.model.Project;
import com.example.authservice.model.ProjectMembership;
import com.example.authservice.model.User;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.authservice.qa.QaSupport.as;
import static com.example.authservice.qa.QaSupport.concurrently;
import static com.example.authservice.qa.QaSupport.json;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
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
 * qa adversarial checks for US-6 (D6, E38, E39, P13): organization removals and demotions carry through to every
 * project of that organization, and only that organization, including when they race with project writes. On top of
 * the Projects fixture, Hal is also an Initech MEMBER with an explicit Portal TESTER role, and Hal is also an explicit
 * Billing MEMBER.
 * <p>
 * The invariant checked after every race (D3 cap, D6 consistency): no project membership row exists for a user who
 * isn't a member of the project's organization, and an organization READER holds no project role above READER.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class QaCarryThroughTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private AcmeFixture acmeFixture;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMembershipRepository projectMembershipRepository;

    private Projects projects;
    private UUID acme;
    private User hal;

    @BeforeEach
    void seed() {
        projects = fixture.seed();
        acme = projects.acme().orgId();
        hal = projects.hal();
        Organization initech = organizationRepository.findById(projects.initech()).orElseThrow();
        acmeFixture.member(initech, hal, OrganizationRole.MEMBER);
        fixture.member(projectRepository.findById(projects.portal()).orElseThrow(), hal, ProjectRole.TESTER);
        fixture.member(projectRepository.findById(projects.billing()).orElseThrow(), hal, ProjectRole.MEMBER);
    }

    @ParameterizedTest(name = "qa AC-6.1: Hal {0} Acme; his Portal membership stays")
    @ValueSource(strings = {"is removed from", "leaves"})
    @Tag("AC-6.1")
    @DisplayName("qa AC-6.1 / E38: leaving or removal deletes the memberships in every Acme project and no other")
    void removalDeletesEveryAcmeProjectMembershipAndNoOther(String change) throws Exception {
        // Given
        User actor = change.equals("leaves") ? hal : projects.acme().ben();

        // When
        MvcResult removed = perform(delete("/api/orgs/" + acme + "/members/" + hal.getId()), actor, null);

        // Then
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);
        assertThat(roleIn(projects.checkout(), hal)).isEmpty();
        assertThat(roleIn(projects.billing(), hal)).isEmpty();
        assertThat(roleIn(projects.portal(), hal)).contains(ProjectRole.TESTER);
        MvcResult portal = perform(get("/internal/projects/" + projects.portal() + "/access"), hal, null);
        assertThat(JsonPath.<String>read(portal.getResponse().getContentAsString(), "$.role")).isEqualTo("TESTER");
        assertThat(projectMembershipRepository.findAll()).hasSize(4);
    }

    @Test
    @Tag("AC-6.1")
    @Tag("AC-7.5")
    @DisplayName("qa AC-6.1: a removed user's earlier token gets 403 from every Acme project read and the access check")
    void aRemovedUsersEarlierTokenGetsNothing() throws Exception {
        // Given
        assertThat(perform(delete("/api/orgs/" + acme + "/members/" + hal.getId()), projects.acme().ben(), null)
                .getResponse().getStatus()).isEqualTo(204);

        // When / Then
        for (String path : List.of(projects(acme), project(acme, projects.checkout()),
                members(acme, projects.checkout()), "/internal/projects/" + projects.checkout() + "/access",
                "/internal/projects/" + projects.billing() + "/access")) {
            assertProblem(perform(get(path), hal, null), 403, "FORBIDDEN", path);
        }
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("qa AC-6.3 / E39: a demotion to READER lowers every Acme project role and no Initech one")
    void aDemotionLowersEveryAcmeProjectRoleAndNoOther() throws Exception {
        // When
        MvcResult changed = perform(patch("/api/orgs/" + acme + "/members/" + hal.getId()), projects.acme().ana(),
                "{\"role\":\"READER\"}");

        // Then
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(roleIn(projects.checkout(), hal)).contains(ProjectRole.READER);
        assertThat(roleIn(projects.billing(), hal)).contains(ProjectRole.READER);
        assertThat(roleIn(projects.portal(), hal)).contains(ProjectRole.TESTER);
        assertThat(roleIn(projects.checkout(), projects.gus())).contains(ProjectRole.MEMBER);
        assertThat(roleIn(projects.checkout(), projects.acme().cai())).contains(ProjectRole.ADMIN);
    }

    @Test
    @Tag("AC-6.3")
    @Tag("AC-5.4")
    @DisplayName("qa A6 / AC-5.4: after a demotion and re-promotion, the role stays READER until an admin changes it")
    void aPromotionDoesntRestoreLoweredRoles() throws Exception {
        // Given
        String orgMember = "/api/orgs/" + acme + "/members/" + projects.gus().getId();
        assertThat(perform(patch(orgMember), projects.acme().ana(), "{\"role\":\"READER\"}").getResponse().getStatus())
                .isEqualTo(200);
        String projectMember = member(acme, projects.checkout(), projects.gus().getId());

        // When
        MvcResult whileReader = perform(patch(projectMember), projects.acme().cai(), "{\"role\":\"MEMBER\"}");
        assertThat(perform(patch(orgMember), projects.acme().ana(), "{\"role\":\"MEMBER\"}").getResponse().getStatus())
                .isEqualTo(200);
        Optional<ProjectRole> afterPromotion = roleIn(projects.checkout(), projects.gus());
        MvcResult afterReassign = perform(patch(projectMember), projects.acme().cai(), "{\"role\":\"MEMBER\"}");

        // Then
        assertProblem(whileReader, 422, "PROJECT_ROLE_NOT_ALLOWED", projectMember);
        assertThat(afterPromotion).contains(ProjectRole.READER);
        assertThat(afterReassign.getResponse().getStatus()).isEqualTo(200);
        assertThat(roleIn(projects.checkout(), projects.gus())).contains(ProjectRole.MEMBER);
    }

    @Test
    @Tag("AC-6.6")
    @DisplayName("qa AC-6.6: an organization ADMIN demoted to MEMBER keeps an explicit project role, not ADMIN")
    void anOrganizationAdminDemotedToMemberKeepsOnlyTheExplicitRole() throws Exception {
        // Given: Ben (Acme ADMIN, implicit project ADMIN) also has an explicit Checkout READER row (AC-4.9)
        fixture.member(projectRepository.findById(projects.checkout()).orElseThrow(), projects.acme().ben(),
                ProjectRole.READER);

        // When
        perform(patch("/api/orgs/" + acme + "/members/" + projects.acme().ben().getId()), projects.acme().ana(),
                "{\"role\":\"MEMBER\"}");

        // Then
        String path = project(acme, projects.checkout());
        MvcResult view = perform(get(path), projects.acme().ben(), null);
        assertThat(JsonPath.<String>read(view.getResponse().getContentAsString(), "$.myRole")).isEqualTo("READER");
        assertProblem(perform(patch(path), projects.acme().ben(), "{\"name\":\"Checkout Web\"}"), 403, "FORBIDDEN",
                path);
    }

    @RepeatedTest(value = 5, name = "qa P13 race: an organization removal and a project add of the same user (run {currentRepetition})")
    @Tag("AC-6.1")
    @Tag("AC-4.4")
    void anOrganizationRemovalRacingWithAProjectAdd() throws Exception {
        // Given
        String orgMember = "/api/orgs/" + acme + "/members/" + projects.ivy().getId();
        String add = members(acme, projects.checkout());

        // When
        List<MvcResult> results = concurrently(
                () -> perform(delete(orgMember), projects.acme().ben(), null),
                () -> perform(post(add), projects.acme().cai(),
                        "{\"userId\":\"" + projects.ivy().getId() + "\",\"role\":\"MEMBER\"}"));

        // Then
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(204);
        if (results.get(1).getResponse().getStatus() != 201) {
            assertProblem(results.get(1), 422, "NOT_ORG_MEMBER", add);
        }
        assertThat(roleIn(projects.checkout(), projects.ivy())).as("no orphaned membership").isEmpty();
        assertInvariant();
    }

    @RepeatedTest(value = 5, name = "qa P13 race: a demotion to READER and a project promotion to ADMIN (run {currentRepetition})")
    @Tag("AC-6.3")
    @Tag("AC-5.4")
    void aDemotionRacingWithAProjectPromotion() throws Exception {
        // Given
        String orgMember = "/api/orgs/" + acme + "/members/" + projects.gus().getId();
        String projectMember = member(acme, projects.checkout(), projects.gus().getId());

        // When
        List<MvcResult> results = concurrently(
                () -> perform(patch(orgMember), projects.acme().ana(), "{\"role\":\"READER\"}"),
                () -> perform(patch(projectMember), projects.acme().cai(), "{\"role\":\"ADMIN\"}"));

        // Then
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
        if (results.get(1).getResponse().getStatus() != 200) {
            assertProblem(results.get(1), 422, "PROJECT_ROLE_NOT_ALLOWED", projectMember);
        }
        assertThat(roleIn(projects.checkout(), projects.gus())).as("the cap holds").contains(ProjectRole.READER);
        assertInvariant();
    }

    @RepeatedTest(value = 5, name = "qa P13 race: a demotion to READER and a project add as ADMIN (run {currentRepetition})")
    @Tag("AC-6.3")
    @Tag("AC-4.6")
    void aDemotionRacingWithAProjectAddAsAdmin() throws Exception {
        // Given
        String orgMember = "/api/orgs/" + acme + "/members/" + projects.ivy().getId();
        String add = members(acme, projects.billing());

        // When
        List<MvcResult> results = concurrently(
                () -> perform(patch(orgMember), projects.acme().ana(), "{\"role\":\"READER\"}"),
                () -> perform(post(add), projects.acme().ben(),
                        "{\"userId\":\"" + projects.ivy().getId() + "\",\"role\":\"ADMIN\"}"));

        // Then
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
        if (results.get(1).getResponse().getStatus() == 201) {
            assertThat(roleIn(projects.billing(), projects.ivy())).contains(ProjectRole.READER);
        } else {
            assertProblem(results.get(1), 422, "PROJECT_ROLE_NOT_ALLOWED", add);
            assertThat(roleIn(projects.billing(), projects.ivy())).isEmpty();
        }
        assertInvariant();
    }

    @RepeatedTest(value = 5, name = "qa P13 race: removal, leave and role change of one user at once (run {currentRepetition})")
    @Tag("AC-6.1")
    @Tag("AC-5.7")
    void aRemovalALeaveAndARoleChangeOfOneUser() throws Exception {
        // Given
        String orgMember = "/api/orgs/" + acme + "/members/" + hal.getId();
        String checkoutHal = member(acme, projects.checkout(), hal.getId());
        String billingHal = member(acme, projects.billing(), hal.getId());

        // When
        List<MvcResult> results = concurrently(
                () -> perform(delete(orgMember), projects.acme().ben(), null),
                () -> perform(delete(checkoutHal), hal, null),
                () -> perform(patch(billingHal), projects.acme().ana(), "{\"role\":\"TESTER\"}"));

        // Then: the organization removal always succeeds; the others either ran first or see its result
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(204);
        int leave = results.get(1).getResponse().getStatus();
        if (leave == 403) {
            // the removal committed before the guard ran
            assertProblem(results.get(1), 403, "FORBIDDEN", checkoutHal);
        } else if (leave != 204) {
            // the guard passed, then the removal deleted the row before the leave took the organization lock
            assertProblem(results.get(1), 404, "MEMBER_NOT_FOUND", checkoutHal);
        }
        int change = results.get(2).getResponse().getStatus();
        if (change != 200) {
            assertProblem(results.get(2), 404, "MEMBER_NOT_FOUND", billingHal);
        }
        assertThat(roleIn(projects.checkout(), hal)).isEmpty();
        assertThat(roleIn(projects.billing(), hal)).isEmpty();
        assertThat(roleIn(projects.portal(), hal)).contains(ProjectRole.TESTER);
        assertInvariant();
    }

    @RepeatedTest(value = 5, name = "qa race: an organization owner removes a project admin while she adds a member (run {currentRepetition})")
    @Tag("AC-6.1")
    void aProjectAdminRemovedFromTheOrganizationWhileAddingAMember() throws Exception {
        // Given
        String orgMember = "/api/orgs/" + acme + "/members/" + projects.acme().cai().getId();
        String add = members(acme, projects.checkout());

        // When
        List<MvcResult> results = concurrently(
                () -> perform(delete(orgMember), projects.acme().ana(), null),
                () -> perform(post(add), projects.acme().cai(),
                        "{\"userId\":\"" + projects.ivy().getId() + "\",\"role\":\"READER\"}"));

        // Then
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(204);
        int added = results.get(1).getResponse().getStatus();
        if (added != 201) {
            assertProblem(results.get(1), 403, "FORBIDDEN", add);
        }
        assertThat(roleIn(projects.checkout(), projects.acme().cai())).isEmpty();
        assertInvariant();
    }

    /**
     * No row for a non-member of the project's organization; no organization READER above project READER.
     */
    private void assertInvariant() {
        for (ProjectMembership membership : projectMembershipRepository.findAll()) {
            Project project = projectRepository.findById(membership.getProject().getId()).orElseThrow();
            Optional<OrganizationRole> orgRole = orgMembershipRepository
                    .findByUserIdAndOrganizationId(membership.getUser().getId(), project.getOrganization().getId())
                    .map(OrgMembership::getRole);
            assertThat(orgRole).as("organization membership behind %s in %s", membership.getUser().getEmail(),
                    project.getName()).isPresent();
            if (orgRole.get() == OrganizationRole.READER) {
                assertThat(membership.getRole()).as("cap for %s in %s", membership.getUser().getEmail(),
                        project.getName()).isEqualTo(ProjectRole.READER);
            }
        }
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, User actor, String body) throws Exception {
        MockHttpServletRequestBuilder withActor = as(request, actor);
        return mockMvc.perform(body == null ? withActor : json(withActor, body)).andReturn();
    }

    private Optional<ProjectRole> roleIn(UUID projectId, User user) {
        return projectMembershipRepository.findByUserIdAndProjectId(user.getId(), projectId)
                .map(ProjectMembership::getRole);
    }
}
