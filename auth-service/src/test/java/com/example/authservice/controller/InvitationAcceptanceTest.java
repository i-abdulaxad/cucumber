package com.example.authservice.controller;

import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.model.Invitation;
import com.example.authservice.model.OrgMembership;
import com.example.authservice.model.Organization;
import com.example.authservice.model.User;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.UserRepository;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-6: accept an invitation, as a new or an existing account (T13), plus the steps of AC-5.9 and AC-5.10 that need
 * the accept flow. Background: the Acme fixture, and Ben has invited fay@example.com as MEMBER and received token T.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationAcceptanceTest {

    private static final String FAY = "fay@example.com";
    private static final String BEN = "ben@example.com";
    private static final String NEW_ACCOUNT = "{\"password\":\"Secret123!\",\"fullName\":\"Fay Lin\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Acme acme;
    private String invitationId;
    private String t;

    @BeforeEach
    void seedAcmeAndInviteFay() throws Exception {
        acme = fixture.seed();
        MvcResult invited = mockMvc.perform(post(invitations())
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, BEN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}"))
                .andReturn();
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        invitationId = JsonPath.read(invited.getResponse().getContentAsString(), "$.id");
        t = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
    }

    @Test
    @Tag("AC-6.1")
    @DisplayName("AC-6.1 A new person creates an account and joins in one step")
    void aNewPersonCreatesAnAccountAndJoinsInOneStep(CapturedOutput output) throws Exception {
        // When
        MvcResult result = acceptAnonymously(t, NEW_ACCOUNT);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$.user.email", String.class)).isEqualTo(FAY);
        assertThat(body.read("$.user.fullName", String.class)).isEqualTo("Fay Lin");
        assertThat(body.read("$.user.status", String.class)).isEqualTo("PENDING_VERIFICATION");
        assertThat(body.read("$.organization.id", String.class)).isEqualTo(acme.orgId().toString());
        assertThat(body.read("$.organization.myRole", String.class)).isEqualTo("MEMBER");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(t).doesNotContainIgnoringCase("password");
        MvcResult fayOrgs = mockMvc.perform(get("/api/orgs")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, FAY)))).andReturn();
        DocumentContext orgs = JsonPath.parse(fayOrgs.getResponse().getContentAsString());
        assertThat(orgs.read("$[*].name", List.class)).containsExactly("Acme QA");
        assertThat(orgs.read("$[0].myRole", String.class)).isEqualTo("MEMBER");
        assertThat(listInvitations(BEN)).isEqualTo("[]");
        assertThat(output.getAll()).doesNotContain(t);
    }

    @Test
    @Tag("AC-6.2")
    @DisplayName("AC-6.2 A person who already has an account accepts while logged in (the member-list step is also "
            + "checked through GET /api/orgs/{acme}/members in MembersTest, T14)")
    void aPersonWithAnAccountAcceptsWhileLoggedIn() throws Exception {
        // Given
        registerFay("Secret123!");
        String fayToken = tokenOf(mockMvc, FAY);

        // When
        MvcResult result = mockMvc.perform(post(accept(t)).header(HttpHeaders.AUTHORIZATION, bearer(fayToken)))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$.organization.id", String.class)).isEqualTo(acme.orgId().toString());
        assertThat(body.read("$.organization.myRole", String.class)).isEqualTo("MEMBER");
        assertThat(body.read("$.user.email", String.class)).isEqualTo(FAY);
        assertThat(membershipOf(FAY)).isEqualTo(OrganizationRole.MEMBER);
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3 An invitation can't be accepted by an account with another email")
    void anInvitationCantBeAcceptedByAnotherEmail() throws Exception {
        // When
        MvcResult result = mockMvc.perform(post(accept(t))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "eve@example.com")))).andReturn();

        // Then
        assertProblem(result, 403, "INVITATION_EMAIL_MISMATCH", accept(t));
        assertThat(orgMembershipRepository.findByUserIdAndOrganizationId(acme.eve().getId(), acme.orgId())).isEmpty();
        assertThat(listInvitations(BEN)).contains(FAY);
    }

    @ParameterizedTest(name = "AC-6.4 {0}: {1} {2}")
    @CsvSource({
            "unknown token,         404, INVITATION_NOT_FOUND",
            "expired,               410, INVITATION_EXPIRED",
            "revoked by Ben,        410, INVITATION_REVOKED",
            "status ACCEPTED,       410, INVITATION_ALREADY_ACCEPTED"
    })
    @Tag("AC-6.4")
    void onlyAPendingUnexpiredInvitationCanBeAccepted(String state, int status, String code) throws Exception {
        // Given
        String token = t;
        switch (state) {
            case "unknown token" -> token = "inv-unknown-QA";
            case "expired" ->
                    updateInvitation(invitation -> invitation.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)));
            case "revoked by Ben" -> assertThat(revoke(BEN, invitationId).getResponse().getStatus()).isEqualTo(204);
            default -> updateInvitation(invitation -> invitation.setStatus(InvitationStatus.ACCEPTED));
        }

        // When
        MvcResult result = acceptAnonymously(token, NEW_ACCOUNT);

        // Then
        assertProblem(result, status, code, accept(token));
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-6.5")
    @DisplayName("AC-6.5 An anonymous caller whose email already has an account must log in first")
    void anAnonymousCallerWhoseEmailHasAnAccountMustLogInFirst() throws Exception {
        // Given
        registerFay("Secret123!");

        // When
        MvcResult result = acceptAnonymously(t, "{\"password\":\"Other123!\",\"fullName\":\"Fay Lin\"}");

        // Then
        assertProblem(result, 409, "EMAIL_ALREADY_REGISTERED", accept(t));
        assertThat(membershipOf(FAY)).isNull();
        assertThat(login(FAY, "Secret123!").getResponse().getStatus()).isEqualTo(200);
        assertThat(login(FAY, "Other123!").getResponse().getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "AC-6.6 {0}")
    @ValueSource(strings = {"no body", "{\"password\":\"short\",\"fullName\":\"Fay Lin\"}", "{\"password\":\"Secret123!\"}"})
    @Tag("AC-6.6")
    void anAnonymousAcceptanceNeedsAValidBody(String body) throws Exception {
        // When
        MockHttpServletRequestBuilder request = post(accept(t));
        if (!body.equals("no body")) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", accept(t));
        List<Map<String, String>> errors = problem.read("$.errors");
        assertThat(errors).isNotEmpty().allSatisfy(error -> assertThat(error.get("message")).isNotBlank());
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
        assertThat(invitationRepository.findByToken(t)).get().extracting(Invitation::getStatus)
                .isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @Tag("AC-6.7")
    @DisplayName("AC-6.7 Accepting when already a member is a conflict, not a server error")
    void acceptingWhenAlreadyAMemberIsAConflict() throws Exception {
        // Given
        seedInvitation("cai@example.com", OrganizationRole.ADMIN, "inv-cai-QA");

        // When
        MvcResult result = mockMvc.perform(post(accept("inv-cai-QA"))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "cai@example.com")))).andReturn();

        // Then
        assertProblem(result, 409, "ALREADY_MEMBER", accept("inv-cai-QA"));
        assertThat(membershipOf("cai@example.com")).isEqualTo(OrganizationRole.MEMBER);
    }

    @Test
    @Tag("AC-6.8")
    @DisplayName("AC-6.8 A failed acceptance leaves no account behind")
    void aFailedAcceptanceLeavesNoAccountBehind() throws Exception {
        // Given
        updateInvitation(invitation -> invitation.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)));
        assertProblem(acceptAnonymously(t, NEW_ACCOUNT), 410, "INVITATION_EXPIRED", accept(t));

        // When
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fay@example.com\",\"password\":\"Secret123!\",\"fullName\":\"Fay Lin\"}"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-6.9")
    @DisplayName("AC-6.9 No one joins when the organization is already full")
    void noOneJoinsWhenTheOrganizationIsFull() throws Exception {
        // Given
        User gus = fixture.user("gus@example.com", "Gus Ray");
        fixture.member(organizationRepository.findById(acme.orgId()).orElseThrow(), gus, OrganizationRole.READER);

        // When
        MvcResult result = acceptAnonymously(t, NEW_ACCOUNT);

        // Then
        assertProblem(result, 409, "SEAT_LIMIT_REACHED", accept(t));
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-5.9")
    @DisplayName("AC-5.9 Revoking a pending invitation kills the token (the last step; the rest is in InvitationsTest)")
    void revokingKillsTheToken() throws Exception {
        // Given
        assertThat(revoke(BEN, invitationId).getResponse().getStatus()).isEqualTo(204);

        // When
        MvcResult result = acceptAnonymously(t, NEW_ACCOUNT);

        // Then
        assertProblem(result, 410, "INVITATION_REVOKED", accept(t));
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-5.10")
    @DisplayName("AC-5.10 Revoking an accepted invitation is INVITATION_NOT_PENDING (row 4; rows 1-3 are in "
            + "InvitationsTest)")
    void revokingAnAcceptedInvitationIsNotPending() throws Exception {
        // Given Fay has accepted invitation I
        assertThat(acceptAnonymously(t, NEW_ACCOUNT).getResponse().getStatus()).isEqualTo(201);

        // When
        MvcResult result = revoke(BEN, invitationId);

        // Then
        assertProblem(result, 409, "INVITATION_NOT_PENDING", invitations() + "/" + invitationId);
        assertThat(membershipOf(FAY)).isEqualTo(OrganizationRole.MEMBER);
    }

    @Test
    @DisplayName("Q2: the 200 and 201 bodies have the same members, and both have user and organization")
    void the200And201BodiesShareOneSchema() throws Exception {
        // Given a second invitation, for an account that exists, and room for both
        Organization stored = organizationRepository.findById(acme.orgId()).orElseThrow();
        stored.setSeatLimit(10);
        organizationRepository.save(stored);
        registerUser("gus@example.com");
        seedInvitation("gus@example.com", OrganizationRole.READER, "inv-gus-QA");

        // When
        MvcResult created = acceptAnonymously(t, NEW_ACCOUNT);
        MvcResult joined = mockMvc.perform(post(accept("inv-gus-QA"))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "gus@example.com")))).andReturn();

        // Then
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(joined.getResponse().getStatus()).isEqualTo(200);
        Map<String, Map<String, Object>> createdBody = JsonPath.read(created.getResponse().getContentAsString(), "$");
        Map<String, Map<String, Object>> joinedBody = JsonPath.read(joined.getResponse().getContentAsString(), "$");
        assertThat(createdBody.keySet()).containsExactlyInAnyOrder("user", "organization");
        assertThat(joinedBody.keySet()).isEqualTo(createdBody.keySet());
        assertThat(joinedBody.get("user").keySet()).isEqualTo(createdBody.get("user").keySet());
        assertThat(joinedBody.get("organization").keySet()).isEqualTo(createdBody.get("organization").keySet());
        assertThat(joinedBody.get("organization")).containsEntry("myRole", "READER");
    }

    @Test
    @DisplayName("Q3: a logged-in caller's well-formed body is ignored, even when it would be invalid")
    void aLoggedInCallersBodyIsIgnored() throws Exception {
        // Given
        registerFay("Secret123!");

        // When
        MvcResult result = mockMvc.perform(post(accept(t))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, FAY)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"short\",\"fullName\":\"\"}")).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(login(FAY, "Secret123!").getResponse().getStatus()).as("password unchanged").isEqualTo(200);
    }

    @Test
    @DisplayName("Q3: an unreadable body is 400 MALFORMED_REQUEST, also for a logged-in caller")
    void anUnreadableBodyIsMalformedForALoggedInCaller() throws Exception {
        // Given
        registerFay("Secret123!");

        // When
        MvcResult result = mockMvc.perform(post(accept(t))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, FAY)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":")).andReturn();

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", accept(t));
        assertThat(membershipOf(FAY)).isNull();
    }

    @Test
    @DisplayName("Q6 accept order: body validation (anonymous) comes before INVITATION_NOT_FOUND")
    void validationComesBeforeNotFound() throws Exception {
        assertProblem(acceptAnonymously("inv-unknown-QA", "{\"password\":\"short\",\"fullName\":\"Fay Lin\"}"), 400,
                "VALIDATION_FAILED", accept("inv-unknown-QA"));
    }

    @Test
    @DisplayName("Q6 accept order: ALREADY_ACCEPTED before REVOKED before EXPIRED")
    void goneReasonsComeInOrder() throws Exception {
        // Given an accepted invitation whose expiry has passed
        updateInvitation(invitation -> {
            invitation.setStatus(InvitationStatus.ACCEPTED);
            invitation.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        });
        assertProblem(acceptAnonymously(t, NEW_ACCOUNT), 410, "INVITATION_ALREADY_ACCEPTED", accept(t));

        // And a revoked one whose expiry has passed
        updateInvitation(invitation -> invitation.setStatus(InvitationStatus.REVOKED));
        assertProblem(acceptAnonymously(t, NEW_ACCOUNT), 410, "INVITATION_REVOKED", accept(t));
    }

    @Test
    @DisplayName("Q6 accept order: a 410 comes before INVITATION_EMAIL_MISMATCH")
    void goneComesBeforeEmailMismatch() throws Exception {
        // Given
        assertThat(revoke(BEN, invitationId).getResponse().getStatus()).isEqualTo(204);

        // Then
        assertProblem(mockMvc.perform(post(accept(t))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "eve@example.com")))).andReturn(),
                410, "INVITATION_REVOKED", accept(t));
    }

    @Test
    @DisplayName("Q6 accept order: INVITATION_EMAIL_MISMATCH comes before ALREADY_MEMBER")
    void emailMismatchComesBeforeAlreadyMember() throws Exception {
        assertProblem(mockMvc.perform(post(accept(t))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "cai@example.com")))).andReturn(),
                403, "INVITATION_EMAIL_MISMATCH", accept(t));
    }

    @Test
    @DisplayName("Q6 accept order: ALREADY_MEMBER comes before SEAT_LIMIT_REACHED (logged-in caller)")
    void alreadyMemberComesBeforeSeatLimit() throws Exception {
        // Given a full organization and an invitation for a member
        User gus = fixture.user("gus@example.com", "Gus Ray");
        fixture.member(organizationRepository.findById(acme.orgId()).orElseThrow(), gus, OrganizationRole.READER);
        seedInvitation("cai@example.com", OrganizationRole.ADMIN, "inv-cai-QA");

        // Then
        assertProblem(mockMvc.perform(post(accept("inv-cai-QA"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "cai@example.com")))).andReturn(),
                409, "ALREADY_MEMBER", accept("inv-cai-QA"));
    }

    @Test
    @DisplayName("Q6 accept order: EMAIL_ALREADY_REGISTERED comes before SEAT_LIMIT_REACHED (anonymous caller)")
    void emailAlreadyRegisteredComesBeforeSeatLimit() throws Exception {
        // Given a full organization, and Fay already has an account
        User gus = fixture.user("gus@example.com", "Gus Ray");
        fixture.member(organizationRepository.findById(acme.orgId()).orElseThrow(), gus, OrganizationRole.READER);
        registerFay("Secret123!");

        // Then
        assertProblem(acceptAnonymously(t, NEW_ACCOUNT), 409, "EMAIL_ALREADY_REGISTERED", accept(t));
    }

    @Test
    @DisplayName("D11: the invitation email matches the logged-in account case-insensitively")
    void theEmailMatchIsCaseInsensitive() throws Exception {
        // Given an invitation stored in lower case (the database requires it, E72), and a login typed in mixed case
        registerUser("gus@example.com");
        seedInvitation("gus@example.com", OrganizationRole.READER, "inv-gus-QA");

        // When
        MvcResult result = mockMvc.perform(post(accept("inv-gus-QA"))
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "Gus@Example.COM")))).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @RepeatedTest(value = 5, name = "Q5 two concurrent anonymous acceptances of one token: one wins, the other gets "
            + "410 INVITATION_ALREADY_ACCEPTED, no 500 (run {currentRepetition} of {totalRepetitions})")
    void twoConcurrentAcceptancesOfOneTokenProduceOneMembership() throws Exception {
        // When
        List<MvcResult> results = concurrently(
                () -> acceptAnonymously(t, NEW_ACCOUNT),
                () -> acceptAnonymously(t, NEW_ACCOUNT));

        // Then
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 410);
        MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 410).findFirst()
                .orElseThrow();
        assertProblem(loser, 410, "INVITATION_ALREADY_ACCEPTED", accept(t));
        assertThat(userRepository.findAll()).filteredOn(user -> user.getEmail().equals(FAY)).hasSize(1);
        assertThat(orgMembershipRepository.findAll())
                .filteredOn(membership -> membership.getUser().getEmail().equals(FAY)).hasSize(1);
    }

    @RepeatedTest(value = 3, name = "Q5 two concurrent logged-in acceptances of one token: one 200, one 410 "
            + "(run {currentRepetition} of {totalRepetitions})")
    void twoConcurrentLoggedInAcceptancesProduceOneMembership() throws Exception {
        // Given
        registerFay("Secret123!");
        String fayToken = tokenOf(mockMvc, FAY);

        // When
        Callable<MvcResult> acceptAsFay = () -> mockMvc.perform(post(accept(t))
                .header(HttpHeaders.AUTHORIZATION, bearer(fayToken))).andReturn();
        List<MvcResult> results = concurrently(acceptAsFay, acceptAsFay);

        // Then
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 410);
        assertThat(orgMembershipRepository.findAll())
                .filteredOn(membership -> membership.getUser().getEmail().equals(FAY)).hasSize(1);
    }

    private String invitations() {
        return "/api/orgs/" + acme.orgId() + "/invitations";
    }

    private static String accept(String token) {
        return "/api/auth/invitations/" + token + "/accept";
    }

    private MvcResult acceptAnonymously(String token, String body) throws Exception {
        return mockMvc.perform(post(accept(token)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult revoke(String actorEmail, String id) throws Exception {
        return mockMvc.perform(delete(invitations() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actorEmail)))).andReturn();
    }

    private String listInvitations(String actorEmail) throws Exception {
        return mockMvc.perform(get(invitations())
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actorEmail))))
                .andReturn().getResponse().getContentAsString();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, password))).andReturn();
    }

    private void registerFay(String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fay@example.com\",\"password\":\"" + password + "\",\"fullName\":\"Fay Lin\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void registerUser(String email) {
        User user = fixture.user(email, "Some One");
        assertThat(passwordEncoder.matches(AcmeFixture.PASSWORD, user.getPasswordHash())).isTrue();
    }

    /**
     * The user's role in Acme, or {@code null} if they aren't a member.
     */
    private OrganizationRole membershipOf(String email) {
        return userRepository.findByEmail(email)
                .flatMap(user -> orgMembershipRepository.findByUserIdAndOrganizationId(user.getId(), acme.orgId()))
                .map(OrgMembership::getRole)
                .orElse(null);
    }

    private void updateInvitation(Consumer<Invitation> change) {
        Invitation invitation = invitationRepository.findByToken(t).orElseThrow();
        change.accept(invitation);
        invitationRepository.save(invitation);
    }

    private void seedInvitation(String email, OrganizationRole role, String token) {
        Instant now = Instant.now();
        invitationRepository.save(Invitation.builder()
                .email(email)
                .organization(organizationRepository.findById(acme.orgId()).orElseThrow())
                .role(role)
                .invitedBy(acme.ben())
                .token(token)
                .status(InvitationStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build());
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
