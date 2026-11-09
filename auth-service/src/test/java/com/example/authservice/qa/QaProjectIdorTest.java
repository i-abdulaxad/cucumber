package com.example.authservice.qa;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.ProjectRole;
import com.example.authservice.model.Organization;
import com.example.authservice.model.Project;
import com.example.authservice.model.ProjectMembership;
import com.example.authservice.model.User;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.example.authservice.qa.QaSupport.as;
import static com.example.authservice.qa.QaSupport.json;
import static com.example.authservice.qa.QaSupport.signedRaw;
import static com.example.authservice.support.ApiCalls.bearer;
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
 * qa adversarial checks for AC-2.3, AC-1.6, AC-4.4 and D10 ("the guard checks that the project belongs to the path's
 * organization"): cross-organization project access, IDOR on {@code orgId}, {@code projectId} and {@code userId},
 * mass assignment in request bodies, and authorization inputs that must be ignored ({@code X-User-Id}, a forged
 * {@code orgs} claim). On top of the Projects fixture:
 * <ul>
 *   <li>Eve, Initech OWNER, is also an Acme ADMIN, so she is an implicit admin in both organizations;</li>
 *   <li>Hal is also an Initech MEMBER with an explicit Portal TESTER role;</li>
 *   <li>Zed (Initech MEMBER only) is an explicit Portal READER.</li>
 * </ul>
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class QaProjectIdorTest {

    enum Write {VIEW, LIST_MEMBERS, RENAME, ARCHIVE, RESTORE, ADD_MEMBER, CHANGE_ROLE, REMOVE_MEMBER}

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private AcmeFixture acmeFixture;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMembershipRepository projectMembershipRepository;

    private Projects projects;
    private UUID acme;
    private User eve;
    private User hal;
    private User zed;

    @BeforeEach
    void seed() {
        projects = fixture.seed();
        acme = projects.acme().orgId();
        eve = projects.acme().eve();
        hal = projects.hal();
        Organization acmeOrg = organizationRepository.findById(acme).orElseThrow();
        Organization initech = organizationRepository.findById(projects.initech()).orElseThrow();
        acmeFixture.member(acmeOrg, eve, OrganizationRole.ADMIN);
        acmeFixture.member(initech, hal, OrganizationRole.MEMBER);
        zed = acmeFixture.user("zed@example.com", "Zed Quinn");
        acmeFixture.member(initech, zed, OrganizationRole.MEMBER);
        Project portal = projectRepository.findById(projects.portal()).orElseThrow();
        fixture.member(portal, hal, ProjectRole.TESTER);
        fixture.member(portal, zed, ProjectRole.READER);
    }

    @ParameterizedTest(name = "qa AC-2.3: Eve (admin in both organizations) {0} Portal through Acme's path: 403")
    @EnumSource(Write.class)
    @Tag("AC-2.3")
    @DisplayName("qa AC-2.3 / D10: a project under another organization's path is 403 even for an admin of both")
    void aProjectUnderTheWrongOrganizationsPathIsForbiddenForAnAdminOfBoth(Write write) throws Exception {
        // Given: Eve is an implicit admin of Portal (Initech OWNER) and of every Acme project (Acme ADMIN)
        assertThat(perform(get(project(projects.initech(), projects.portal())), eve, null).getResponse().getStatus())
                .as("Eve sees Portal through Initech's path").isEqualTo(200);

        // When / Then
        assertForbiddenAndPortalUnchanged(write, acme, eve);
    }

    @ParameterizedTest(name = "qa AC-2.3: Ana (Acme OWNER) {0} Checkout through Initech's path: 403")
    @EnumSource(Write.class)
    @Tag("AC-2.3")
    @DisplayName("qa AC-2.3: Ana, an Acme owner who is also an Initech member, can't use Initech's path for Checkout")
    void anAcmeOwnerCantReachCheckoutThroughInitechsPath(Write write) throws Exception {
        // Given
        acmeFixture.member(organizationRepository.findById(projects.initech()).orElseThrow(), projects.acme().ana(),
                OrganizationRole.ADMIN);

        // When
        MvcResult result = perform(request(write, projects.initech(), projects.checkout()), projects.acme().ana(),
                body(write));

        // Then
        assertProblem(result, 403, "FORBIDDEN", path(write, projects.initech(), projects.checkout()));
        Project checkout = projectRepository.findById(projects.checkout()).orElseThrow();
        assertThat(checkout.getName()).isEqualTo("Checkout");
        assertThat(checkout.getArchivedAt()).isNull();
        assertThat(checkout.getOrganization().getId()).isEqualTo(acme);
        assertThat(roleIn(projects.checkout(), hal)).contains(ProjectRole.TESTER);
        assertThat(roleIn(projects.checkout(), projects.ivy())).isEmpty();
    }

    @ParameterizedTest(name = "qa IDOR userId: {0} targets Zed (Portal only) through Checkout")
    @ValueSource(strings = {"PATCH", "DELETE"})
    @Tag("AC-5.3")
    @DisplayName("qa IDOR: a member of another organization's project can't be changed or removed through Checkout")
    void aMemberOfAnotherOrganizationsProjectCantBeTargetedThroughCheckout(String method) throws Exception {
        // Given
        String path = member(acme, projects.checkout(), zed.getId());

        // When
        MvcResult result = method.equals("PATCH")
                ? perform(patch(path), projects.acme().ana(), "{\"role\":\"ADMIN\"}")
                : perform(delete(path), projects.acme().ana(), null);

        // Then
        assertProblem(result, 404, "MEMBER_NOT_FOUND", path);
        assertThat(roleIn(projects.portal(), zed)).contains(ProjectRole.READER);
    }

    @ParameterizedTest(name = "qa IDOR userId: Cai {0} Hal through Checkout touches only Checkout")
    @ValueSource(strings = {"PATCH", "DELETE"})
    @Tag("AC-5.1")
    @Tag("AC-5.6")
    @DisplayName("qa IDOR: a change through Checkout's path never touches the same user's Portal membership")
    void aChangeThroughCheckoutNeverTouchesTheSameUsersPortalMembership(String method) throws Exception {
        // Given
        String path = member(acme, projects.checkout(), hal.getId());

        // When
        MvcResult result = method.equals("PATCH")
                ? perform(patch(path), projects.acme().cai(), "{\"role\":\"ADMIN\"}")
                : perform(delete(path), projects.acme().cai(), null);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(method.equals("PATCH") ? 200 : 204);
        assertThat(roleIn(projects.checkout(), hal))
                .isEqualTo(method.equals("PATCH") ? Optional.of(ProjectRole.ADMIN) : Optional.empty());
        assertThat(roleIn(projects.portal(), hal)).contains(ProjectRole.TESTER);
    }

    @Test
    @Tag("AC-4.4")
    @DisplayName("qa AC-4.4: a member of another organization can't be added to Checkout")
    void aMemberOfAnotherOrganizationCantBeAdded() throws Exception {
        // Given
        String path = members(acme, projects.checkout());

        // When
        MvcResult result = perform(post(path), projects.acme().ana(),
                "{\"userId\":\"" + zed.getId() + "\",\"role\":\"READER\"}");

        // Then
        assertProblem(result, 422, "NOT_ORG_MEMBER", path);
        assertThat(roleIn(projects.checkout(), zed)).isEmpty();
    }

    @Test
    @Tag("AC-4.4")
    @DisplayName("qa AC-4.4: NOT_ORG_MEMBER answers an unknown id and another organization's member identically")
    void notOrgMemberAnswersUnknownAndForeignUsersIdentically() throws Exception {
        // Given
        String path = members(acme, projects.checkout());

        // When
        MvcResult foreign = perform(post(path), projects.acme().ana(),
                "{\"userId\":\"" + zed.getId() + "\",\"role\":\"READER\"}");
        MvcResult unknown = perform(post(path), projects.acme().ana(),
                "{\"userId\":\"" + AcmeFixture.UNKNOWN_ID + "\",\"role\":\"READER\"}");

        // Then
        assertThat(foreign.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    @Tag("AC-2.3")
    @DisplayName("qa AC-2.3: no access, another organization's project and an unknown project give identical 403s")
    void the403sAreIndistinguishable() throws Exception {
        // When
        String noAccess = perform(get(project(acme, projects.billing())), projects.acme().cai(), null)
                .getResponse().getContentAsString();
        String otherOrg = perform(get(project(acme, projects.portal())), projects.acme().cai(), null)
                .getResponse().getContentAsString();
        String unknown = perform(get(project(acme, AcmeFixture.UNKNOWN_ID)), projects.acme().cai(), null)
                .getResponse().getContentAsString();

        // Then: identical apart from the instance (the request path)
        assertThat(withoutInstance(noAccess)).isEqualTo(withoutInstance(otherOrg)).isEqualTo(withoutInstance(unknown));
        assertThat(noAccess + otherOrg + unknown).doesNotContain("Billing", "Portal", projects.initech().toString());
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("qa mass assignment: create ignores id, orgId, createdAt, archivedAt and myRole in the body")
    void createIgnoresServerOwnedFields() throws Exception {
        // Given
        UUID suppliedId = UUID.randomUUID();
        String body = "{\"name\":\"Smuggled\",\"id\":\"" + suppliedId + "\",\"orgId\":\"" + projects.initech()
                + "\",\"createdAt\":\"2000-01-01T00:00:00\",\"archivedAt\":\"2000-01-01T00:00:00\",\"myRole\":\"READER\"}";

        // When
        MvcResult result = perform(post(projects(acme)), projects.acme().ana(), body);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String json = result.getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertThat(id).isNotEqualTo(suppliedId);
        assertThat(JsonPath.<String>read(json, "$.orgId")).isEqualTo(acme.toString());
        assertThat(JsonPath.<Object>read(json, "$.archivedAt")).isNull();
        assertThat(JsonPath.<String>read(json, "$.myRole")).isEqualTo("ADMIN");
        Project stored = projectRepository.findById(id).orElseThrow();
        assertThat(stored.getOrganization().getId()).isEqualTo(acme);
        assertThat(stored.getCreatedAt()).isAfter(Instant.now().minus(5, ChronoUnit.MINUTES));
        assertThat(projectRepository.findByOrganizationId(projects.initech())).hasSize(1);
    }

    @Test
    @Tag("AC-3.1")
    @DisplayName("qa mass assignment: rename changes only the name, never the organization or the archive state")
    void renameChangesOnlyTheName() throws Exception {
        // Given
        String path = project(acme, projects.checkout());
        String body = "{\"name\":\"Checkout Web\",\"orgId\":\"" + projects.initech()
                + "\",\"archivedAt\":\"2000-01-01T00:00:00\",\"id\":\"" + projects.portal() + "\"}";

        // When
        MvcResult result = perform(patch(path), projects.acme().cai(), body);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Project checkout = projectRepository.findById(projects.checkout()).orElseThrow();
        assertThat(checkout.getName()).isEqualTo("Checkout Web");
        assertThat(checkout.getOrganization().getId()).isEqualTo(acme);
        assertThat(checkout.getArchivedAt()).isNull();
        assertThat(projectRepository.findById(projects.portal()).orElseThrow().getName()).isEqualTo("Portal");
    }

    @Test
    @Tag("AC-4.2")
    @DisplayName("qa mass assignment: an add with a projectId or orgId in the body adds to the path's project only")
    void addUsesThePathsProjectOnly() throws Exception {
        // Given
        String path = members(acme, projects.checkout());
        String body = "{\"userId\":\"" + projects.ivy().getId() + "\",\"role\":\"READER\",\"projectId\":\""
                + projects.portal() + "\",\"orgId\":\"" + projects.initech() + "\",\"joinedAt\":\"2000-01-01T00:00:00\"}";

        // When
        MvcResult result = perform(post(path), projects.acme().cai(), body);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(roleIn(projects.checkout(), projects.ivy())).contains(ProjectRole.READER);
        assertThat(roleIn(projects.portal(), projects.ivy())).isEmpty();
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.joinedAt"))
                .doesNotStartWith("2000");
    }

    @ParameterizedTest(name = "qa D10: X-User-Id of {0} is ignored")
    @ValueSource(strings = {"ana", "cai"})
    @Tag("AC-3.1")
    @DisplayName("qa P5: the X-User-Id header is never used for project authorization")
    void xUserIdIsIgnored(String impersonated) throws Exception {
        // Given
        User admin = impersonated.equals("ana") ? projects.acme().ana() : projects.acme().cai();
        String path = project(acme, projects.checkout());

        // When
        MvcResult rename = perform(patch(path).header("X-User-Id", admin.getId().toString()), projects.gus(),
                "{\"name\":\"Hijacked\"}");
        MvcResult view = perform(get(path).header("X-User-Id", admin.getId().toString()), projects.ivy(), null);

        // Then
        assertProblem(rename, 403, "FORBIDDEN", path);
        assertProblem(view, 403, "FORBIDDEN", path);
        assertThat(projectRepository.findById(projects.checkout()).orElseThrow().getName()).isEqualTo("Checkout");
    }

    @Test
    @Tag("AC-3.1")
    @Tag("AC-7.1")
    @DisplayName("qa P5 / C2: forged orgs and projects claims in a validly signed token grant nothing")
    void forgedRoleClaimsGrantNothing() throws Exception {
        // Given: a token signed with the shared secret whose claims say Ivy is Acme OWNER and Checkout ADMIN
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + projects.ivy().getId() + "\",\"email\":\"ivy@example.com\",\"orgs\":[{\"orgId\":\""
                + acme + "\",\"role\":\"OWNER\"}],\"projects\":[{\"projectId\":\"" + projects.checkout()
                + "\",\"role\":\"ADMIN\"}],\"mfa\":false,\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}";
        String token = signedRaw("{\"alg\":\"HS512\"}", payload);
        String path = project(acme, projects.checkout());
        String access = "/internal/projects/" + projects.checkout() + "/access";

        // When
        MvcResult rename = mockMvc.perform(json(patch(path).header(HttpHeaders.AUTHORIZATION, bearer(token)),
                "{\"name\":\"Hijacked\"}")).andReturn();
        MvcResult check = mockMvc.perform(get(access).header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();
        MvcResult create = mockMvc.perform(json(post(projects(acme)).header(HttpHeaders.AUTHORIZATION, bearer(token)),
                "{\"name\":\"Hijacked\"}")).andReturn();

        // Then
        assertProblem(rename, 403, "FORBIDDEN", path);
        assertProblem(check, 403, "FORBIDDEN", access);
        assertProblem(create, 403, "FORBIDDEN", projects(acme));
        assertThat(projectRepository.findAll()).extracting(Project::getName).doesNotContain("Hijacked");
    }

    @ParameterizedTest(name = "qa C5: the access check ignores {0}")
    @ValueSource(strings = {"?userId=ANA", "?user=ANA&projectId=CHECKOUT", "X-User-Id"})
    @Tag("AC-7.1")
    @DisplayName("qa C5: the access check answers for the token's user only, never for a user named in the request")
    void theAccessCheckAnswersForTheTokensUserOnly(String variant) throws Exception {
        // Given
        String path = "/internal/projects/" + projects.checkout() + "/access";
        String ana = projects.acme().ana().getId().toString();
        MockHttpServletRequestBuilder request = variant.equals("X-User-Id")
                ? get(path).header("X-User-Id", ana)
                : get(path + variant.replace("ANA", ana).replace("CHECKOUT", projects.checkout().toString()));

        // When
        MvcResult result = perform(request, projects.ivy(), null);

        // Then
        assertProblem(result, 403, "FORBIDDEN", path);
    }

    @Test
    @Tag("AC-7.1")
    @DisplayName("qa C5: Hal's access check reports each project's own organization and role")
    void theAccessCheckReportsEachProjectsOwnOrganization() throws Exception {
        // When
        MvcResult checkout = perform(get("/internal/projects/" + projects.checkout() + "/access"), hal, null);
        MvcResult portal = perform(get("/internal/projects/" + projects.portal() + "/access"), hal, null);

        // Then
        assertThat(JsonPath.<Map<String, Object>>read(checkout.getResponse().getContentAsString(), "$")).isEqualTo(
                Map.of("projectId", projects.checkout().toString(), "orgId", acme.toString(), "role", "TESTER",
                        "archived", false));
        assertThat(JsonPath.<Map<String, Object>>read(portal.getResponse().getContentAsString(), "$")).isEqualTo(
                Map.of("projectId", projects.portal().toString(), "orgId", projects.initech().toString(),
                        "role", "TESTER", "archived", false));
    }

    @Test
    @Tag("AC-2.1")
    @DisplayName("qa AC-2.1: an organization's project list never contains another organization's project")
    void theListNeverContainsAnotherOrganizationsProject() throws Exception {
        // When
        MvcResult acmeList = perform(get(projects(acme)), eve, null);
        MvcResult initechList = perform(get(projects(projects.initech())), eve, null);

        // Then
        assertThat(JsonPath.<List<String>>read(acmeList.getResponse().getContentAsString(), "$[*].name"))
                .containsExactly("Billing", "Checkout");
        assertThat(JsonPath.<List<String>>read(initechList.getResponse().getContentAsString(), "$[*].name"))
                .containsExactly("Portal");
    }

    private void assertForbiddenAndPortalUnchanged(Write write, UUID pathOrg, User actor) throws Exception {
        MvcResult result = perform(request(write, pathOrg, projects.portal()), actor, body(write));
        assertProblem(result, 403, "FORBIDDEN", path(write, pathOrg, projects.portal()));
        Project portal = projectRepository.findById(projects.portal()).orElseThrow();
        assertThat(portal.getName()).isEqualTo("Portal");
        assertThat(portal.getArchivedAt()).isNull();
        assertThat(portal.getOrganization().getId()).isEqualTo(projects.initech());
        assertThat(roleIn(projects.portal(), hal)).contains(ProjectRole.TESTER);
        assertThat(roleIn(projects.portal(), zed)).contains(ProjectRole.READER);
        assertThat(roleIn(projects.portal(), projects.ivy())).isEmpty();
    }

    private MockHttpServletRequestBuilder request(Write write, UUID orgId, UUID projectId) {
        String path = path(write, orgId, projectId);
        return switch (write) {
            case VIEW, LIST_MEMBERS -> get(path);
            case RENAME, CHANGE_ROLE -> patch(path);
            case ARCHIVE, RESTORE, ADD_MEMBER -> post(path);
            case REMOVE_MEMBER -> delete(path);
        };
    }

    private String path(Write write, UUID orgId, UUID projectId) {
        return switch (write) {
            case VIEW, RENAME -> project(orgId, projectId);
            case LIST_MEMBERS, ADD_MEMBER -> members(orgId, projectId);
            case ARCHIVE -> project(orgId, projectId) + "/archive";
            case RESTORE -> project(orgId, projectId) + "/restore";
            case CHANGE_ROLE, REMOVE_MEMBER -> member(orgId, projectId, hal.getId());
        };
    }

    private String body(Write write) {
        return switch (write) {
            case RENAME -> "{\"name\":\"Hijacked\"}";
            case ADD_MEMBER -> "{\"userId\":\"" + projects.ivy().getId() + "\",\"role\":\"ADMIN\"}";
            case CHANGE_ROLE -> "{\"role\":\"ADMIN\"}";
            default -> null;
        };
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, User actor, String body) throws Exception {
        MockHttpServletRequestBuilder withActor = as(request, actor);
        return mockMvc.perform(body == null ? withActor : json(withActor, body)).andReturn();
    }

    private Optional<ProjectRole> roleIn(UUID projectId, User user) {
        return projectMembershipRepository.findByUserIdAndProjectId(user.getId(), projectId)
                .map(ProjectMembership::getRole);
    }

    private static String withoutInstance(String problemJson) {
        return problemJson.replaceAll("\"instance\":\"[^\"]*\"", "");
    }
}
