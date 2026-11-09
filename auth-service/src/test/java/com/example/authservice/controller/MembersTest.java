package com.example.authservice.controller;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.model.OrgMembership;
import com.example.authservice.model.User;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-7 and US-8: view members, change their roles, remove them, leave (T14). Also the member-list steps of AC-4.1
 * and AC-6.2, which need GET /api/orgs/{orgId}/members.
 */
@AuthServiceIntegrationTest
class MembersTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    @ParameterizedTest(name = "AC-7.1 {0} lists the members: {1}")
    @CsvSource({"ana, 200", "dan, 200", "eve, 403"})
    @Tag("AC-7.1")
    void everyMemberCanSeeTheMemberList(String actor, int status) throws Exception {
        // When
        MvcResult result = mockMvc.perform(get(members())
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actor + "@example.com")))).andReturn();

        // Then
        if (status == 403) {
            assertProblem(result, 403, "FORBIDDEN", members());
            return;
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$[*].email", List.class))
                .containsExactly("ana@example.com", "ben@example.com", "cai@example.com", "dan@example.com");
        assertThat(body.read("$[*].role", List.class)).containsExactly("OWNER", "ADMIN", "MEMBER", "READER");
        assertThat(body.read("$[*].userId", List.class)).containsExactly(acme.ana().getId().toString(),
                acme.ben().getId().toString(), acme.cai().getId().toString(), acme.dan().getId().toString());
        assertThat(body.read("$[*].fullName", List.class)).containsExactly("Ana Lima", "Ben Ode", "Cai Wu", "Dan Roe");
        List<Map<String, Object>> items = body.read("$");
        assertThat(items).allSatisfy(item -> {
            assertThat(item).containsOnlyKeys("userId", "email", "fullName", "role", "joinedAt");
            assertThat(item.get("joinedAt")).isNotNull();
        });
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");
    }

    @ParameterizedTest(name = "AC-7.2 {0} changes {1} to {2}: {3} {4}")
    @CsvSource({
            "ana, cai, ADMIN,  200, ''",
            "ana, ben, OWNER,  200, ''",
            "ben, cai, READER, 200, ''",
            "ben, dan, ADMIN,  200, ''",
            "ben, cai, OWNER,  422, ROLE_CEILING",
            "ben, ana, ADMIN,  422, ROLE_CEILING",
            "cai, dan, MEMBER, 403, FORBIDDEN",
            "dan, cai, READER, 403, FORBIDDEN",
            "eve, cai, READER, 403, FORBIDDEN"
    })
    @Tag("AC-7.2")
    void whoMayChangeWhichRole(String actor, String target, OrganizationRole newRole, int status, String code)
            throws Exception {
        // Given
        User targetUser = user(target);
        OrganizationRole before = roleOf(targetUser);

        // When
        MvcResult result = changeRole(actor, targetUser.getId(), "{\"role\":\"" + newRole + "\"}");

        // Then
        if (status == 200) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
            assertThat(body.read("$.userId", String.class)).isEqualTo(targetUser.getId().toString());
            assertThat(body.read("$.role", String.class)).isEqualTo(newRole.name());
            assertThat(roleOf(targetUser)).isEqualTo(newRole);
        } else {
            assertProblem(result, status, code, member(targetUser.getId()));
            assertThat(roleOf(targetUser)).isEqualTo(before);
        }
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("AC-7.3 Nobody can change their own role")
    void nobodyCanChangeTheirOwnRole() throws Exception {
        // When
        MvcResult result = changeRole("ana", acme.ana().getId(), "{\"role\":\"ADMIN\"}");

        // Then
        assertProblem(result, 422, "SELF_ROLE_CHANGE", member(acme.ana().getId()));
        assertThat(roleOf(acme.ana())).isEqualTo(OrganizationRole.OWNER);
    }

    @Test
    @DisplayName("D11: a member below ADMIN who targets their own role also gets SELF_ROLE_CHANGE")
    void aReaderChangingTheirOwnRoleGetsSelfRoleChange() throws Exception {
        assertProblem(changeRole("dan", acme.dan().getId(), "{\"role\":\"READER\"}"), 422, "SELF_ROLE_CHANGE",
                member(acme.dan().getId()));
    }

    @ParameterizedTest(name = "AC-7.4 target {0}")
    @ValueSource(strings = {"eve", "unknown"})
    @Tag("AC-7.4")
    void theTargetMustBeAMemberOfThisOrganization(String target) throws Exception {
        // Given
        UUID targetId = target.equals("eve") ? acme.eve().getId() : AcmeFixture.UNKNOWN_ID;

        // When
        MvcResult result = changeRole("ana", targetId, "{\"role\":\"READER\"}");

        // Then
        assertProblem(result, 404, "MEMBER_NOT_FOUND", member(targetId));
    }

    @ParameterizedTest(name = "AC-7.5 body {0}: {1}")
    @CsvSource(delimiter = '|', value = {"{}|VALIDATION_FAILED", "{\"role\":\"GOD\"}|MALFORMED_REQUEST"})
    @Tag("AC-7.5")
    void anInvalidRoleIsRejected(String body, String code) throws Exception {
        // When
        MvcResult result = changeRole("ana", acme.cai().getId(), body);

        // Then
        assertProblem(result, 400, code, member(acme.cai().getId()));
        assertThat(roleOf(acme.cai())).isEqualTo(OrganizationRole.MEMBER);
    }

    @Test
    @Tag("AC-7.6")
    @DisplayName("AC-7.6 A demotion takes effect at once, even for a token issued earlier")
    void aDemotionTakesEffectAtOnce() throws Exception {
        // Given
        String benToken = tokenOf(mockMvc, "ben@example.com");
        assertThat(changeRole("ana", acme.ben().getId(), "{\"role\":\"MEMBER\"}").getResponse().getStatus())
                .isEqualTo(200);

        // When
        String invitations = "/api/orgs/" + acme.orgId() + "/invitations";
        MvcResult result = mockMvc.perform(post(invitations).header(HttpHeaders.AUTHORIZATION, bearer(benToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"fay@example.com\",\"role\":\"READER\"}")).andReturn();

        // Then
        assertProblem(result, 403, "FORBIDDEN", invitations);
    }

    @Test
    @Tag("AC-7.7")
    @DisplayName("AC-7.7 Setting the role a member already has is a no-op success")
    void settingTheCurrentRoleIsANoOp() throws Exception {
        // When
        MvcResult result = changeRole("ana", acme.cai().getId(), "{\"role\":\"MEMBER\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(roleOf(acme.cai())).isEqualTo(OrganizationRole.MEMBER);
    }

    @ParameterizedTest(name = "AC-8.1 {0} removes {1}: {2} {3}")
    @CsvSource({
            "ana, ben, 204, ''",
            "ben, cai, 204, ''",
            "ben, dan, 204, ''",
            "ben, ana, 422, ROLE_CEILING",
            "cai, dan, 403, FORBIDDEN",
            "dan, cai, 403, FORBIDDEN",
            "eve, cai, 403, FORBIDDEN"
    })
    @Tag("AC-8.1")
    void whoMayRemoveWhom(String actor, String target, int status, String code) throws Exception {
        // Given
        User targetUser = user(target);

        // When
        MvcResult result = remove(actor, targetUser.getId());

        // Then
        if (status == 204) {
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(roleOf(targetUser)).isNull();
        } else {
            assertProblem(result, status, code, member(targetUser.getId()));
            assertThat(roleOf(targetUser)).isNotNull();
        }
    }

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 A removed member loses access at once")
    void aRemovedMemberLosesAccessAtOnce() throws Exception {
        // Given
        String caiToken = tokenOf(mockMvc, "cai@example.com");
        assertThat(remove("ben", acme.cai().getId()).getResponse().getStatus()).isEqualTo(204);

        // When
        String acmePath = "/api/orgs/" + acme.orgId();
        MvcResult view = mockMvc.perform(get(acmePath).header(HttpHeaders.AUTHORIZATION, bearer(caiToken))).andReturn();
        MvcResult list = mockMvc.perform(get("/api/orgs").header(HttpHeaders.AUTHORIZATION, bearer(caiToken)))
                .andReturn();

        // Then
        assertProblem(view, 403, "FORBIDDEN", acmePath);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @ParameterizedTest(name = "AC-8.3 {0} leaves")
    @ValueSource(strings = {"ben", "cai", "dan"})
    @Tag("AC-8.3")
    void anyMemberCanLeave(String actor) throws Exception {
        // Given
        User leaver = user(actor);

        // When
        MvcResult result = remove(actor, leaver.getId());

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(roleOf(leaver)).isNull();
    }

    @ParameterizedTest(name = "AC-8.4 Ana leaves when {0}")
    @ValueSource(strings = {"nothing else", "Ana has changed Ben's role to OWNER"})
    @Tag("AC-8.4")
    void theLastOwnerCantLeave(String setup) throws Exception {
        // Given
        if (!setup.equals("nothing else")) {
            assertThat(changeRole("ana", acme.ben().getId(), "{\"role\":\"OWNER\"}").getResponse().getStatus())
                    .isEqualTo(200);
        }

        // When
        MvcResult result = remove("ana", acme.ana().getId());

        // Then
        if (setup.equals("nothing else")) {
            assertProblem(result, 409, "LAST_OWNER", member(acme.ana().getId()));
            assertThat(roleOf(acme.ana())).isEqualTo(OrganizationRole.OWNER);
        } else {
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(roleOf(acme.ana())).isNull();
        }
    }

    @Test
    @Tag("AC-8.5")
    @DisplayName("AC-8.5 Removing someone who isn't a member is not found")
    void removingANonMemberIsNotFound() throws Exception {
        assertProblem(remove("ana", acme.eve().getId()), 404, "MEMBER_NOT_FOUND", member(acme.eve().getId()));
    }

    @Test
    @Tag("AC-8.6")
    @DisplayName("AC-8.6 Removing a member frees a seat")
    void removingAMemberFreesASeat() throws Exception {
        // Given
        User gus = fixture.user("gus@example.com", "Gus Ray");
        fixture.member(organizationRepository.findById(acme.orgId()).orElseThrow(), gus, OrganizationRole.READER);
        assertThat(remove("ben", gus.getId()).getResponse().getStatus()).isEqualTo(204);

        // When
        MvcResult result = mockMvc.perform(post("/api/orgs/" + acme.orgId() + "/invitations")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ben@example.com")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}")).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("Q6 remove: an ADMIN removing an unknown user gets MEMBER_NOT_FOUND, not a 403")
    void anAdminRemovingAnUnknownUserGetsMemberNotFound() throws Exception {
        assertProblem(remove("ben", AcmeFixture.UNKNOWN_ID), 404, "MEMBER_NOT_FOUND", member(AcmeFixture.UNKNOWN_ID));
    }

    @Test
    @DisplayName("Q6 change order: SELF_ROLE_CHANGE comes before ROLE_CEILING")
    void selfRoleChangeComesBeforeRoleCeiling() throws Exception {
        assertProblem(changeRole("ben", acme.ben().getId(), "{\"role\":\"OWNER\"}"), 422, "SELF_ROLE_CHANGE",
                member(acme.ben().getId()));
    }

    @Test
    @DisplayName("D11: a non-member can't leave an organization (403, not 404)")
    void aNonMemberCantLeave() throws Exception {
        assertProblem(remove("eve", acme.eve().getId()), 403, "FORBIDDEN", member(acme.eve().getId()));
    }

    @Test
    @Tag("AC-4.1")
    @DisplayName("AC-4.1 (members-list step) The creator of a new organization is its only member, as OWNER")
    void theCreatorIsTheOnlyMemberOfANewOrganization() throws Exception {
        // Given
        String eveToken = tokenOf(mockMvc, "eve@example.com");
        MvcResult created = mockMvc.perform(post("/api/orgs").header(HttpHeaders.AUTHORIZATION, bearer(eveToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Globex QA\"}")).andReturn();
        String globexId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // When
        MvcResult result = mockMvc.perform(get("/api/orgs/" + globexId + "/members")
                .header(HttpHeaders.AUTHORIZATION, bearer(eveToken))).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$[*].email", List.class)).containsExactly("eve@example.com");
        assertThat(body.read("$[0].role", String.class)).isEqualTo("OWNER");
    }

    @Test
    @Tag("AC-6.2")
    @DisplayName("AC-6.2 (members-list step) After accepting while logged in, Ben's member list includes Fay")
    void anAcceptedMemberAppearsInTheMemberList() throws Exception {
        // Given Ben has invited Fay, and Fay has registered and accepted while logged in
        String token = JsonPath.read(mockMvc.perform(post("/api/orgs/" + acme.orgId() + "/invitations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ben@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}")).andReturn()
                .getResponse().getContentAsString(), "$.token");
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"fay@example.com\",\"password\":\"Secret123!\",\"fullName\":\"Fay Lin\"}"));
        assertThat(mockMvc.perform(post("/api/auth/invitations/" + token + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "fay@example.com")))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);

        // When
        MvcResult result = mockMvc.perform(get(members())
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ben@example.com")))).andReturn();

        // Then
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$[?(@.email == 'fay@example.com')].role", List.class)).containsExactly("MEMBER");
    }

    @RepeatedTest(value = 3, name = "NFR: two owners leaving at the same time can't leave the organization without "
            + "an owner (run {currentRepetition} of {totalRepetitions})")
    void twoOwnersLeavingAtTheSameTimeKeepOneOwner() throws Exception {
        // Given
        OrgMembership ben = orgMembershipRepository.findByUserIdAndOrganizationId(acme.ben().getId(), acme.orgId())
                .orElseThrow();
        ben.setRole(OrganizationRole.OWNER);
        orgMembershipRepository.save(ben);
        String anaToken = tokenOf(mockMvc, "ana@example.com");
        String benToken = tokenOf(mockMvc, "ben@example.com");

        // When
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(delete(member(acme.ana().getId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(anaToken))).andReturn(),
                () -> mockMvc.perform(delete(member(acme.ben().getId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(benToken))).andReturn());

        // Then
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(204, 409);
        assertThat(orgMembershipRepository.countByOrganizationIdAndRole(acme.orgId(), OrganizationRole.OWNER))
                .isEqualTo(1);
    }

    private String members() {
        return "/api/orgs/" + acme.orgId() + "/members";
    }

    private String member(UUID userId) {
        return members() + "/" + userId;
    }

    private MvcResult changeRole(String actor, UUID targetId, String body) throws Exception {
        return mockMvc.perform(patch(member(targetId))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actor + "@example.com")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult remove(String actor, UUID targetId) throws Exception {
        return mockMvc.perform(delete(member(targetId))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actor + "@example.com")))).andReturn();
    }

    private User user(String name) {
        return switch (name) {
            case "ana" -> acme.ana();
            case "ben" -> acme.ben();
            case "cai" -> acme.cai();
            case "dan" -> acme.dan();
            default -> acme.eve();
        };
    }

    /**
     * The user's role in Acme, or {@code null} if they aren't a member.
     */
    private OrganizationRole roleOf(User user) {
        return orgMembershipRepository.findByUserIdAndOrganizationId(user.getId(), acme.orgId())
                .map(OrgMembership::getRole)
                .orElse(null);
    }

    @SafeVarargs
    private static List<MvcResult> concurrently(Callable<MvcResult>... calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
