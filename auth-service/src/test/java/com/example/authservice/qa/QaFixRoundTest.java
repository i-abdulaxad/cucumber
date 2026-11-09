package com.example.authservice.qa;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.authservice.qa.QaSupport.as;
import static com.example.authservice.qa.QaSupport.concurrently;
import static com.example.authservice.qa.QaSupport.json;
import static com.example.authservice.qa.QaSupport.seedPendingInvitation;
import static com.example.authservice.qa.QaSupport.statuses;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, fix round (D13, T16–T20): edges around the 7 new ACs that the spec's example rows don't cover. The full revoke
 * ceiling matrix (AC-5.12), more numeric and digit-string roles (AC-5.11, AC-7.8), multi-byte over-long login
 * passwords (AC-2.10), the registration race through the accept flow (AC-2.11), more accept body shapes (AC-6.10),
 * and real row locks held by another connection on the invitation and the organization rows (AC-1.9), with no test
 * hook in the code under test.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class QaFixRoundTest {

    private static final String NEW_ACCOUNT = "{\"password\":\"Secret123!\",\"fullName\":\"Fay Lin\"}";
    private static final String FAY = "fay@example.com";
    private static final Pattern ERROR_ENTRY = Pattern.compile("(?m)^\\S+\\s+ERROR\\s");
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s+at\\s+[\\w$.]+\\(");
    private static final Pattern LOCK_WARN_ENTRY =
            Pattern.compile("(?m)^\\S+\\s+WARN\\s.*Request rejected because a lock could not be acquired");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private DataSource dataSource;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    // ---------------------------------------------------------------- AC-5.12

    @ParameterizedTest(name = "AC-5.12 (qa full matrix) {0} revokes a {1} invitation: {2} {3}")
    @CsvSource({
            "ana, OWNER, 204, ''", "ana, ADMIN, 204, ''", "ana, MEMBER, 204, ''", "ana, READER, 204, ''",
            "ben, OWNER, 422, ROLE_CEILING", "ben, ADMIN, 204, ''", "ben, MEMBER, 204, ''", "ben, READER, 204, ''",
            "cai, OWNER, 403, FORBIDDEN", "cai, ADMIN, 403, FORBIDDEN", "cai, MEMBER, 403, FORBIDDEN",
            "cai, READER, 403, FORBIDDEN",
            "dan, OWNER, 403, FORBIDDEN", "dan, ADMIN, 403, FORBIDDEN", "dan, MEMBER, 403, FORBIDDEN",
            "dan, READER, 403, FORBIDDEN",
            "eve, OWNER, 403, FORBIDDEN", "eve, ADMIN, 403, FORBIDDEN", "eve, MEMBER, 403, FORBIDDEN",
            "eve, READER, 403, FORBIDDEN"
    })
    @Tag("AC-5.12")
    void everyRoleRevokesAnInvitationOfEveryRole(String actor, OrganizationRole role, int status, String code)
            throws Exception {
        // Given
        Invitation invitation = seedPendingInvitation(invitationRepository, acmeOrg(), acme.ana(), FAY, role,
                "inv-ceiling-QA");
        String path = invitations() + "/" + invitation.getId();

        // When
        MvcResult result = mockMvc.perform(as(delete(path), user(actor))).andReturn();

        // Then
        InvitationStatus after = invitationRepository.findById(invitation.getId()).orElseThrow().getStatus();
        if (status == 204) {
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(after).isEqualTo(InvitationStatus.REVOKED);
        } else {
            assertProblem(result, status, code, path);
            assertThat(after).isEqualTo(InvitationStatus.PENDING);
        }
    }

    @ParameterizedTest(name = "AC-5.12 (qa) D13 order: Ben revokes a {0} OWNER invitation: 409 INVITATION_NOT_PENDING "
            + "before ROLE_CEILING")
    @ValueSource(strings = {"ACCEPTED", "REVOKED", "EXPIRED"})
    @Tag("AC-5.12")
    void notPendingComesBeforeTheCeilingForEveryFinalStatus(InvitationStatus status) throws Exception {
        // Given
        Invitation invitation = QaSupport.seedInvitation(invitationRepository, acmeOrg(), acme.ana(), FAY,
                OrganizationRole.OWNER, status, Instant.now().plus(7, ChronoUnit.DAYS), "inv-order-QA");
        String path = invitations() + "/" + invitation.getId();

        // When
        MvcResult result = mockMvc.perform(as(delete(path), acme.ben())).andReturn();

        // Then
        assertProblem(result, 409, "INVITATION_NOT_PENDING", path);
        assertThat(invitationRepository.findById(invitation.getId())).get().extracting(Invitation::getStatus)
                .isEqualTo(status);
    }

    @Test
    @Tag("AC-5.12")
    @DisplayName("AC-5.12 (qa) D13 order: Ben revoking another organization's OWNER invitation through Acme's path is "
            + "404 INVITATION_NOT_FOUND, not ROLE_CEILING")
    void notFoundComesBeforeTheCeilingAcrossOrganizations() throws Exception {
        // Given
        Organization initech = fixture.organization("Initech QA");
        fixture.member(initech, fixture.user("ivy@example.com", "Ivy Ng"), OrganizationRole.OWNER);
        Invitation invitation = seedPendingInvitation(invitationRepository, initech, acme.ana(), FAY,
                OrganizationRole.OWNER, "inv-order-idor-QA");
        String path = invitations() + "/" + invitation.getId();

        // When
        MvcResult result = mockMvc.perform(as(delete(path), acme.ben())).andReturn();

        // Then
        assertProblem(result, 404, "INVITATION_NOT_FOUND", path);
        assertThat(invitationRepository.findById(invitation.getId())).get().extracting(Invitation::getStatus)
                .isEqualTo(InvitationStatus.PENDING);
    }

    // ---------------------------------------------------------------- AC-5.11, AC-7.8

    @ParameterizedTest(name = "AC-5.11 (qa) Ana invites with role {0}: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"2", "-1", "4", "2.0", "\"0\"", "\"01\"", "\"-1\"", "\" 1\"", "\"1.0\""})
    @Tag("AC-5.11")
    void moreNumericRolesAreRejectedWhenInviting(String role) throws Exception {
        // When
        MvcResult result = mockMvc.perform(json(as(post(invitations()), acme.ana()),
                "{\"email\":\"" + FAY + "\",\"role\":" + role + "}")).andReturn();

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", invitations());
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    @Tag("AC-5.11")
    @DisplayName("AC-5.11 (qa) an ADMIN sending role 0 gets 400 MALFORMED_REQUEST, not the OWNER ceiling answer 422")
    void aNumericRoleIsRejectedBeforeTheCeiling() throws Exception {
        // When
        MvcResult result = mockMvc.perform(json(as(post(invitations()), acme.ben()),
                "{\"email\":\"" + FAY + "\",\"role\":0}")).andReturn();

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", invitations());
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @ParameterizedTest(name = "AC-7.8 (qa) Ana changes Dan to role {0}: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"2", "3", "-1", "4", "\"0\"", "\"3\"", "\"01\""})
    @Tag("AC-7.8")
    void moreNumericRolesAreRejectedWhenChangingARole(String role) throws Exception {
        // When
        MvcResult result = mockMvc.perform(json(as(patch(member(acme.dan())), acme.ana()), "{\"role\":" + role + "}"))
                .andReturn();

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", member(acme.dan()));
        assertThat(roleOf(acme.dan())).isEqualTo(OrganizationRole.READER);
    }

    @Test
    @Tag("AC-5.11")
    @Tag("AC-7.8")
    @DisplayName("AC-5.11 / AC-7.8 (qa) T17 must-not: string matching is unchanged, \"MEMBER\" works and \"Member\" "
            + "is 400")
    void stringRolesStillMatchExactly() throws Exception {
        // When
        MvcResult wrongCase = mockMvc.perform(json(as(post(invitations()), acme.ana()),
                "{\"email\":\"" + FAY + "\",\"role\":\"Member\"}")).andReturn();
        MvcResult invited = mockMvc.perform(json(as(post(invitations()), acme.ana()),
                "{\"email\":\"" + FAY + "\",\"role\":\"MEMBER\"}")).andReturn();
        MvcResult changed = mockMvc.perform(json(as(patch(member(acme.dan())), acme.ana()), "{\"role\":\"MEMBER\"}"))
                .andReturn();

        // Then
        assertProblem(wrongCase, 400, "MALFORMED_REQUEST", invitations());
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(roleOf(acme.dan())).isEqualTo(OrganizationRole.MEMBER);
    }

    // ---------------------------------------------------------------- AC-2.10

    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 (qa) an account whose password is 18 emoji (72 bytes) rejects 18 emoji + \"X\" like a "
            + "wrong password, and accepts the exact password")
    void anEmojiPasswordWithASuffixIsInvalidCredentials() throws Exception {
        // Given
        String emoji = "😀".repeat(18);
        register("emoji72@example.com", emoji);
        DocumentContext wrong = assertProblem(login("emoji72@example.com", "Wrong123!"), 401, "INVALID_CREDENTIALS",
                "/api/auth/login");

        // When
        MvcResult suffixed = login("emoji72@example.com", emoji + "X");
        MvcResult exact = login("emoji72@example.com", emoji);

        // Then
        DocumentContext problem = assertProblem(suffixed, 401, "INVALID_CREDENTIALS", "/api/auth/login");
        assertThat(problem.read("$.detail", String.class)).isEqualTo(wrong.read("$.detail", String.class));
        assertThat(suffixed.getResponse().getContentAsString()).doesNotContain("\"errors\"");
        assertThat(exact.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 / AC-2.4 (qa) a 73-byte password for an unknown email gets the same answer as a wrong one")
    void anOverlongPasswordForAnUnknownEmailIsTheUniformAnswer() throws Exception {
        // When
        MvcResult overlong = login("nobody@example.com", "A".repeat(73));
        MvcResult wrong = login("nobody@example.com", "Wrong123!");

        // Then
        DocumentContext a = assertProblem(overlong, 401, "INVALID_CREDENTIALS", "/api/auth/login");
        DocumentContext b = assertProblem(wrong, 401, "INVALID_CREDENTIALS", "/api/auth/login");
        assertThat(a.read("$.detail", String.class)).isEqualTo(b.read("$.detail", String.class));
        assertThat(overlong.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    // ---------------------------------------------------------------- AC-2.11

    @RepeatedTest(value = 5, name = "AC-2.11 (qa) two anonymous acceptances of two invitations for one email race: one "
            + "joins, the other is EMAIL_ALREADY_REGISTERED and leaves nothing (run {currentRepetition})")
    @Tag("AC-2.11")
    void twoAnonymousAcceptancesForOneEmailRace() throws Exception {
        // Given Fay is invited to Acme and to Initech
        Organization initech = fixture.organization("Initech QA");
        fixture.member(initech, fixture.user("ivy@example.com", "Ivy Ng"), OrganizationRole.OWNER);
        seedPendingInvitation(invitationRepository, acmeOrg(), acme.ben(), FAY, OrganizationRole.MEMBER,
                "inv-race-acme-QA");
        seedPendingInvitation(invitationRepository, initech, acme.ben(), FAY, OrganizationRole.MEMBER,
                "inv-race-initech-QA");

        // When
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(json(post(accept("inv-race-acme-QA")), NEW_ACCOUNT)).andReturn(),
                () -> mockMvc.perform(json(post(accept("inv-race-initech-QA")), NEW_ACCOUNT)).andReturn());

        // Then
        assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409);
        int loser = results.get(0).getResponse().getStatus() == 409 ? 0 : 1;
        assertProblem(results.get(loser), 409, "EMAIL_ALREADY_REGISTERED",
                accept(loser == 0 ? "inv-race-acme-QA" : "inv-race-initech-QA"));
        User fay = userRepository.findByEmail(FAY).orElseThrow();
        assertThat(userRepository.findAll()).filteredOn(user -> user.getEmail().equals(FAY)).hasSize(1);
        assertThat(orgMembershipRepository.findAll()).filteredOn(m -> m.getUser().getId().equals(fay.getId()))
                .hasSize(1);
        assertThat(invitationRepository.findByToken(loser == 0 ? "inv-race-acme-QA" : "inv-race-initech-QA"))
                .get().extracting(Invitation::getStatus).isEqualTo(InvitationStatus.PENDING);
    }

    @RepeatedTest(value = 5, name = "AC-2.11 (qa) a registration and an anonymous acceptance of one email race: one "
            + "wins, the other is EMAIL_ALREADY_REGISTERED (run {currentRepetition})")
    @Tag("AC-2.11")
    void aRegistrationAndAnAcceptanceForOneEmailRace() throws Exception {
        // Given
        seedPendingInvitation(invitationRepository, acmeOrg(), acme.ben(), FAY, OrganizationRole.MEMBER,
                "inv-race-reg-QA");

        // When
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(json(post("/api/auth/register"),
                        "{\"email\":\"Fay@Example.com\",\"password\":\"Secret123!\",\"fullName\":\"Fay\"}")).andReturn(),
                () -> mockMvc.perform(json(post(accept("inv-race-reg-QA")), NEW_ACCOUNT)).andReturn());

        // Then
        assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409);
        if (results.get(0).getResponse().getStatus() == 409) {
            assertProblem(results.get(0), 409, "EMAIL_ALREADY_REGISTERED", "/api/auth/register");
        } else {
            assertProblem(results.get(1), 409, "EMAIL_ALREADY_REGISTERED", accept("inv-race-reg-QA"));
            assertThat(orgMembershipRepository.countByOrganizationId(acme.orgId())).isEqualTo(4);
        }
        assertThat(userRepository.findAll()).filteredOn(user -> user.getEmail().equals(FAY)).hasSize(1);
    }

    // ---------------------------------------------------------------- AC-6.10

    @Test
    @Tag("AC-6.10")
    @DisplayName("AC-6.10 (qa) the JSON literal null as the accept body gives the same two entries as {}")
    void aNullLiteralBodyIsLikeAnEmptyObject() throws Exception {
        // Given
        String path = accept(seedFayInvitation());

        // When
        MvcResult nullLiteral = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("null"))
                .andReturn();
        MvcResult emptyObject = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        // Then
        DocumentContext problem = assertProblem(nullLiteral, 400, "VALIDATION_FAILED", path);
        List<Map<String, String>> errors = problem.read("$.errors");
        assertThat(errors).extracting(error -> error.get("field")).containsExactlyInAnyOrder("fullName", "password");
        assertThat(errors).isEqualTo(assertProblem(emptyObject, 400, "VALIDATION_FAILED", path).read("$.errors"));
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-6.10")
    @DisplayName("AC-6.10 (qa) a body with only fullName reports only password")
    void aPartialBodyReportsOnlyTheMissingField() throws Exception {
        // Given
        String path = accept(seedFayInvitation());

        // When
        MvcResult result = mockMvc.perform(json(post(path), "{\"fullName\":\"Fay Lin\"}")).andReturn();

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path);
        List<String> fields = problem.read("$.errors[*].field");
        assertThat(fields).containsExactly("password");
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-6.10")
    @Tag("AC-1.2")
    @DisplayName("AC-6.10 / AC-1.2 (qa) a whitespace-only JSON body is unreadable: 400 MALFORMED_REQUEST")
    void aWhitespaceBodyIsMalformed() throws Exception {
        // Given
        String path = accept(seedFayInvitation());

        // When
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("   "))
                .andReturn();

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", path);
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-6.10")
    @Tag("AC-6.2")
    @DisplayName("AC-6.10 (qa) T20 must-not: a logged-in caller with no body is not validated and joins (200)")
    void aLoggedInCallerWithoutABodyIsNotValidated() throws Exception {
        // Given
        User fay = fixture.user(FAY, "Fay Lin");
        String path = accept(seedFayInvitation());

        // When
        MvcResult result = mockMvc.perform(as(post(path), fay)).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(orgMembershipRepository.findByUserIdAndOrganizationId(fay.getId(), acme.orgId())).isPresent();
    }

    @Test
    @Tag("AC-6.10")
    @DisplayName("AC-6.10 (qa) D11 order: with no body, validation comes before INVITATION_NOT_FOUND")
    void validationComesBeforeAnUnknownToken() throws Exception {
        // When
        MvcResult result = mockMvc.perform(post(accept("no-such-token-QA"))).andReturn();

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", accept("no-such-token-QA"));
        List<String> fields = problem.read("$.errors[*].field");
        assertThat(fields).containsExactlyInAnyOrder("fullName", "password");
    }

    // ---------------------------------------------------------------- AC-1.9 with real locks

    @Test
    @Tag("AC-1.9")
    @DisplayName("AC-1.9 (qa, real lock) revoking while another connection holds the invitation row is 409 CONFLICT, "
            + "and the invitation stays PENDING")
    void revokingALockedInvitationIsAConflict(CapturedOutput output) throws Exception {
        // Given
        Invitation invitation = seedPendingInvitation(invitationRepository, acmeOrg(), acme.ana(), FAY,
                OrganizationRole.MEMBER, "inv-lock-revoke-QA");
        String path = invitations() + "/" + invitation.getId();

        // When
        String[] log = new String[1];
        MvcResult result = whileRowIsLocked("invitations", invitation.getId(), output, log,
                () -> mockMvc.perform(as(delete(path), acme.ana())).andReturn());

        // Then
        assertProblem(result, 409, "CONFLICT", path);
        assertCleanLockLog(log[0], invitation.getId().toString(), "inv-lock-revoke-QA", FAY);
        assertThat(invitationRepository.findById(invitation.getId())).get().extracting(Invitation::getStatus)
                .isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @Tag("AC-1.9")
    @DisplayName("AC-1.9 (qa, real lock) an anonymous accept while the invitation row is locked is 409 CONFLICT, "
            + "creates no account and never logs the acceptance token")
    void acceptingALockedInvitationIsAConflict(CapturedOutput output) throws Exception {
        // Given
        Invitation invitation = seedPendingInvitation(invitationRepository, acmeOrg(), acme.ben(), FAY,
                OrganizationRole.MEMBER, "inv-lock-accept-QA");

        // When
        String[] log = new String[1];
        MvcResult result = whileRowIsLocked("invitations", invitation.getId(), output, log,
                () -> mockMvc.perform(json(post(accept("inv-lock-accept-QA")), NEW_ACCOUNT)).andReturn());

        // Then
        assertProblem(result, 409, "CONFLICT", accept("inv-lock-accept-QA"));
        assertCleanLockLog(log[0], "inv-lock-accept-QA", FAY, "Secret123!");
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
    }

    @Test
    @Tag("AC-1.9")
    @Tag("AC-6.8")
    @DisplayName("AC-1.9 / AC-6.8 (qa, real lock) an anonymous accept that times out on the organization lock (seat "
            + "check) is 409 CONFLICT and leaves no account, membership or accepted invitation behind")
    void acceptingIntoALockedOrganizationLeavesNoAccount(CapturedOutput output) throws Exception {
        // Given
        seedPendingInvitation(invitationRepository, acmeOrg(), acme.ben(), FAY, OrganizationRole.MEMBER,
                "inv-lock-org-QA");

        // When
        String[] log = new String[1];
        MvcResult result = whileRowIsLocked("organizations", acme.orgId(), output, log,
                () -> mockMvc.perform(json(post(accept("inv-lock-org-QA")), NEW_ACCOUNT)).andReturn());

        // Then
        assertProblem(result, 409, "CONFLICT", accept("inv-lock-org-QA"));
        assertCleanLockLog(log[0], "inv-lock-org-QA", FAY, acme.orgId().toString());
        assertThat(userRepository.findByEmail(FAY)).isEmpty();
        assertThat(orgMembershipRepository.countByOrganizationId(acme.orgId())).isEqualTo(4);
        assertThat(invitationRepository.findByToken("inv-lock-org-QA")).get().extracting(Invitation::getStatus)
                .isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @Tag("AC-1.9")
    @DisplayName("AC-1.9 (qa, real lock) a role change while the organization row is locked is 409 CONFLICT, and "
            + "Dan's role is unchanged")
    void changingARoleInALockedOrganizationIsAConflict(CapturedOutput output) throws Exception {
        // When
        String[] log = new String[1];
        MvcResult result = whileRowIsLocked("organizations", acme.orgId(), output, log,
                () -> mockMvc.perform(json(as(patch(member(acme.dan())), acme.ana()), "{\"role\":\"MEMBER\"}"))
                        .andReturn());

        // Then
        assertProblem(result, 409, "CONFLICT", member(acme.dan()));
        assertCleanLockLog(log[0], acme.orgId().toString(), acme.dan().getId().toString());
        assertThat(roleOf(acme.dan())).isEqualTo(OrganizationRole.READER);
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Call {
        MvcResult run() throws Exception;
    }

    /**
     * Holds SELECT … FOR UPDATE on one row from another connection while the call runs; returns the call's log.
     */
    private MvcResult whileRowIsLocked(String table, UUID id, CapturedOutput output, String[] log, Call call)
            throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, id);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).as(table + " row is locked").isTrue();
                }
            }
            int before = output.getAll().length();
            MvcResult result = call.run();
            log[0] = output.getAll().substring(before);
            holder.rollback();
            return result;
        }
    }

    /**
     * No ERROR, no stack trace, exactly one handler WARN; Hibernate's own WARN lines show the SQL with placeholders
     * only, so none of the given bound values (ids, token, email, password) may appear in the request's log.
     */
    private static void assertCleanLockLog(String requestLog, String... boundValues) {
        assertThat(count(ERROR_ENTRY, requestLog)).as("ERROR entries").isZero();
        assertThat(count(STACK_FRAME, requestLog)).as("stack trace lines").isZero();
        assertThat(count(LOCK_WARN_ENTRY, requestLog)).as("the handler's WARN entry").isEqualTo(1);
        assertThat(requestLog).doesNotContain(boundValues);
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String seedFayInvitation() {
        String token = "inv-body-QA-" + UUID.randomUUID();
        seedPendingInvitation(invitationRepository, acmeOrg(), acme.ben(), FAY, OrganizationRole.MEMBER, token);
        return token;
    }

    private void register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/auth/register"),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"E\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("registration of " + email).isEqualTo(201);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(json(post("/api/auth/login"), loginBody(email, password))).andReturn();
    }

    private OrganizationRole roleOf(User user) {
        return orgMembershipRepository.findByUserIdAndOrganizationId(user.getId(), acme.orgId())
                .map(OrgMembership::getRole).orElse(null);
    }

    private Organization acmeOrg() {
        return organizationRepository.findById(acme.orgId()).orElseThrow();
    }

    private User user(String name) {
        return switch (name) {
            case "ana" -> acme.ana();
            case "ben" -> acme.ben();
            case "cai" -> acme.cai();
            case "dan" -> acme.dan();
            case "eve" -> acme.eve();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private String invitations() {
        return "/api/orgs/" + acme.orgId() + "/invitations";
    }

    private String member(User user) {
        return "/api/orgs/" + acme.orgId() + "/members/" + user.getId();
    }

    private static String accept(String token) {
        return "/api/auth/invitations/" + token + "/accept";
    }
}
