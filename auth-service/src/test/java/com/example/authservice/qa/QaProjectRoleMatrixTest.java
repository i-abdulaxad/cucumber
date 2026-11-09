package com.example.authservice.qa;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.ProjectRole;
import com.example.authservice.model.Organization;
import com.example.authservice.model.Project;
import com.example.authservice.model.ProjectMembership;
import com.example.authservice.model.User;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.example.authservice.qa.QaSupport.as;
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
 * qa matrix for D3 and D4: every organization role (OWNER, ADMIN, MEMBER, READER, or none) × every explicit project
 * role (ADMIN, MEMBER, TESTER, READER, or none) × every project operation, 25 × 13 = 325 cases. The expected answer
 * is computed from the spec's effective-role rule, not from the code:
 * <ul>
 *   <li>not a member of the project's organization: no access, even with a membership row;</li>
 *   <li>organization OWNER or ADMIN: implicit project ADMIN;</li>
 *   <li>otherwise the explicit role, capped at READER for an organization READER (rows above the cap can only be
 *       seeded directly, as a stale row; the resolver must still cap them).</li>
 * </ul>
 * Each case seeds its own small organization, so writes don't influence other cases. Tokens are minted with the
 * TEST-ONLY secret and carry an empty {@code orgs} claim, which also proves the claim is never used.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class QaProjectRoleMatrixTest {

    enum Op {
        VIEW, LIST, LIST_MEMBERS, ACCESS_CHECK, CREATE, RENAME, ARCHIVE, RESTORE, ADD_MEMBER, CHANGE_ROLE,
        REMOVE_OTHER, LEAVE, CHANGE_OWN_ROLE
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture acmeFixture;

    @Autowired
    private ProjectFixture projectFixture;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMembershipRepository projectMembershipRepository;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        List<OrganizationRole> orgRoles = new ArrayList<>(Arrays.asList(OrganizationRole.values()));
        orgRoles.add(null);
        List<ProjectRole> projectRoles = new ArrayList<>(Arrays.asList(ProjectRole.values()));
        projectRoles.add(null);
        for (OrganizationRole orgRole : orgRoles) {
            for (ProjectRole explicit : projectRoles) {
                for (Op op : Op.values()) {
                    cases.add(Arguments.of(orgRole == null ? "NONE" : orgRole.name(),
                            explicit == null ? "NONE" : explicit.name(), op));
                }
            }
        }
        return cases.stream();
    }

    /**
     * D3, from the spec text: the effective project role, or empty for no access.
     */
    static Optional<ProjectRole> effective(OrganizationRole orgRole, ProjectRole explicit) {
        if (orgRole == null) {
            return Optional.empty();
        }
        if (orgRole == OrganizationRole.OWNER || orgRole == OrganizationRole.ADMIN) {
            return Optional.of(ProjectRole.ADMIN);
        }
        if (explicit == null) {
            return Optional.empty();
        }
        return Optional.of(orgRole == OrganizationRole.READER ? ProjectRole.READER : explicit);
    }

    @ParameterizedTest(name = "org {0} × project {1} × {2}")
    @MethodSource("matrix")
    @Tag("AC-2.1")
    @Tag("AC-2.2")
    @Tag("AC-3.1")
    @Tag("AC-3.4")
    @Tag("AC-3.7")
    @Tag("AC-4.1")
    @Tag("AC-4.3")
    @Tag("AC-5.1")
    @Tag("AC-5.2")
    @Tag("AC-5.6")
    @Tag("AC-5.7")
    @Tag("AC-5.9")
    @Tag("AC-7.1")
    @Tag("AC-1.2")
    @DisplayName("qa D3/D4 matrix: organization role × explicit project role × operation")
    void everyOrganizationRoleTimesEveryProjectRoleTimesEveryOperation(String orgRoleName, String explicitName, Op op)
            throws Exception {
        // Given
        OrganizationRole orgRole = orgRoleName.equals("NONE") ? null : OrganizationRole.valueOf(orgRoleName);
        ProjectRole explicit = explicitName.equals("NONE") ? null : ProjectRole.valueOf(explicitName);
        Optional<ProjectRole> effective = effective(orgRole, explicit);
        boolean admin = effective.filter(role -> role == ProjectRole.ADMIN).isPresent();

        acmeFixture.reset();
        User owner = acmeFixture.user("owner@example.com", "Owner Matrix");
        User actor = acmeFixture.user("actor@example.com", "Actor Matrix");
        User target = acmeFixture.user("target@example.com", "Target Matrix");
        User candidate = acmeFixture.user("candidate@example.com", "Candidate Matrix");
        Organization org = acmeFixture.organization("Matrix Org");
        acmeFixture.member(org, owner, OrganizationRole.OWNER);
        acmeFixture.member(org, target, OrganizationRole.MEMBER);
        acmeFixture.member(org, candidate, OrganizationRole.MEMBER);
        if (orgRole != null) {
            acmeFixture.member(org, actor, orgRole);
        } else {
            Organization other = acmeFixture.organization("Other Org");
            acmeFixture.member(other, actor, OrganizationRole.OWNER);
        }
        Project project = projectFixture.project(org, "P");
        if (op == Op.RESTORE) {
            project.setArchivedAt(Instant.now().minus(1, ChronoUnit.DAYS));
            project = projectRepository.save(project);
        }
        projectFixture.member(project, target, ProjectRole.TESTER);
        if (explicit != null) {
            projectFixture.member(project, actor, explicit);
        }
        UUID orgId = org.getId();
        UUID projectId = project.getId();

        // When / Then
        switch (op) {
            case VIEW -> {
                String path = project(orgId, projectId);
                MvcResult result = perform(get(path), actor, null);
                if (effective.isPresent()) {
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    assertThat(QaSupport.read(result, "$.myRole")).isEqualTo(effective.get().name());
                } else {
                    assertProblem(result, 403, "FORBIDDEN", path);
                }
            }
            case LIST -> {
                String path = projects(orgId);
                MvcResult result = perform(get(path), actor, null);
                if (orgRole == null) {
                    assertProblem(result, 403, "FORBIDDEN", path);
                } else {
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    List<Map<String, Object>> listed = JsonPath.read(result.getResponse().getContentAsString(), "$");
                    if (effective.isPresent()) {
                        assertThat(listed).singleElement().satisfies(item -> {
                            assertThat(item.get("id")).isEqualTo(projectId.toString());
                            assertThat(item.get("myRole")).isEqualTo(effective.get().name());
                        });
                    } else {
                        assertThat(listed).isEmpty();
                    }
                }
            }
            case LIST_MEMBERS -> {
                String path = members(orgId, projectId);
                MvcResult result = perform(get(path), actor, null);
                if (effective.isPresent()) {
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    List<String> emails = JsonPath.read(result.getResponse().getContentAsString(), "$[*].email");
                    assertThat(emails).contains("target@example.com");
                } else {
                    assertProblem(result, 403, "FORBIDDEN", path);
                }
            }
            case ACCESS_CHECK -> {
                String path = "/internal/projects/" + projectId + "/access";
                MvcResult result = perform(get(path), actor, null);
                if (effective.isPresent()) {
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
                    assertThat(body).isEqualTo(Map.of("projectId", projectId.toString(), "orgId", orgId.toString(),
                            "role", effective.get().name(), "archived", false));
                } else {
                    assertProblem(result, 403, "FORBIDDEN", path);
                }
            }
            case CREATE -> {
                String path = projects(orgId);
                MvcResult result = perform(post(path), actor, "{\"name\":\"Created By Actor\"}");
                boolean allowed = orgRole == OrganizationRole.OWNER || orgRole == OrganizationRole.ADMIN;
                if (allowed) {
                    assertThat(result.getResponse().getStatus()).isEqualTo(201);
                    assertThat(QaSupport.read(result, "$.myRole")).isEqualTo("ADMIN");
                } else {
                    assertProblem(result, 403, "FORBIDDEN", path);
                }
                assertThat(projectRepository.findByOrganizationId(orgId)).hasSize(allowed ? 2 : 1);
            }
            case RENAME -> {
                String path = project(orgId, projectId);
                MvcResult result = perform(patch(path), actor, "{\"name\":\"Renamed\"}");
                expectAdminOnly(result, admin, 200, path);
                assertThat(projectRepository.findById(projectId).orElseThrow().getName())
                        .isEqualTo(admin ? "Renamed" : "P");
            }
            case ARCHIVE -> {
                String path = project(orgId, projectId) + "/archive";
                MvcResult result = perform(post(path), actor, null);
                expectAdminOnly(result, admin, 200, path);
                assertThat(projectRepository.findById(projectId).orElseThrow().getArchivedAt() != null)
                        .isEqualTo(admin);
            }
            case RESTORE -> {
                String path = project(orgId, projectId) + "/restore";
                MvcResult result = perform(post(path), actor, null);
                expectAdminOnly(result, admin, 200, path);
                assertThat(projectRepository.findById(projectId).orElseThrow().getArchivedAt() == null)
                        .isEqualTo(admin);
            }
            case ADD_MEMBER -> {
                String path = members(orgId, projectId);
                MvcResult result = perform(post(path), actor,
                        "{\"userId\":\"" + candidate.getId() + "\",\"role\":\"READER\"}");
                expectAdminOnly(result, admin, 201, path);
                assertThat(roleOf(candidate, projectId)).isEqualTo(admin ? Optional.of(ProjectRole.READER)
                        : Optional.empty());
            }
            case CHANGE_ROLE -> {
                String path = member(orgId, projectId, target.getId());
                MvcResult result = perform(patch(path), actor, "{\"role\":\"READER\"}");
                expectAdminOnly(result, admin, 200, path);
                assertThat(roleOf(target, projectId)).contains(admin ? ProjectRole.READER : ProjectRole.TESTER);
            }
            case REMOVE_OTHER -> {
                String path = member(orgId, projectId, target.getId());
                MvcResult result = perform(delete(path), actor, null);
                expectAdminOnly(result, admin, 204, path);
                assertThat(roleOf(target, projectId)).isEqualTo(admin ? Optional.empty()
                        : Optional.of(ProjectRole.TESTER));
            }
            case LEAVE -> {
                String path = member(orgId, projectId, actor.getId());
                MvcResult result = perform(delete(path), actor, null);
                if (effective.isEmpty()) {
                    assertProblem(result, 403, "FORBIDDEN", path);
                    assertThat(roleOf(actor, projectId)).isEqualTo(Optional.ofNullable(explicit));
                } else if (explicit == null) {
                    // AC-5.9: an implicit admin has no row to remove.
                    assertProblem(result, 404, "MEMBER_NOT_FOUND", path);
                } else {
                    assertThat(result.getResponse().getStatus()).isEqualTo(204);
                    assertThat(roleOf(actor, projectId)).isEmpty();
                }
            }
            case CHANGE_OWN_ROLE -> {
                String path = member(orgId, projectId, actor.getId());
                MvcResult result = perform(patch(path), actor, "{\"role\":\"READER\"}");
                if (effective.isEmpty()) {
                    assertProblem(result, 403, "FORBIDDEN", path);
                } else if (explicit == null) {
                    // D6 change order: MEMBER_NOT_FOUND before SELF_ROLE_CHANGE.
                    assertProblem(result, 404, "MEMBER_NOT_FOUND", path);
                } else {
                    assertProblem(result, 422, "SELF_ROLE_CHANGE", path);
                }
                assertThat(roleOf(actor, projectId)).isEqualTo(Optional.ofNullable(explicit));
            }
        }
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, User actor, String body) throws Exception {
        MockHttpServletRequestBuilder withActor = as(request, actor);
        return mockMvc.perform(body == null ? withActor : json(withActor, body)).andReturn();
    }

    private static void expectAdminOnly(MvcResult result, boolean admin, int success, String path) throws Exception {
        if (admin) {
            assertThat(result.getResponse().getStatus()).isEqualTo(success);
        } else {
            assertProblem(result, 403, "FORBIDDEN", path);
        }
    }

    private Optional<ProjectRole> roleOf(User user, UUID projectId) {
        return projectMembershipRepository.findByUserIdAndProjectId(user.getId(), projectId)
                .map(ProjectMembership::getRole);
    }
}
