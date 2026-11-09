package com.example.authservice.controller;

import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.model.Invitation;
import com.example.authservice.model.Organization;
import com.example.authservice.model.User;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-5: invite people to an organization, list pending invitations and revoke them (T12).
 * AC-5.9's last step (accepting a revoked token) and AC-5.10 row 4 (revoking an accepted invitation) need the accept
 * flow and are in InvitationAcceptanceTest (T13).
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationsTest {

    private static final String BEN = "ben@example.com";
    private static final String FAY = "fay@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Acme acme;
    private String invitations;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
        invitations = "/api/orgs/" + acme.orgId() + "/invitations";
    }

    @Test
    @Tag("AC-5.1")
    @DisplayName("AC-5.1 An admin invites a colleague and receives the acceptance token once")
    void anAdminInvitesAColleagueAndReceivesTheTokenOnce(CapturedOutput output) throws Exception {
        // When
        MvcResult result = invite(BEN, "{\"email\":\"Fay@Example.com\",\"role\":\"MEMBER\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(UUID.fromString(body.read("$.id", String.class))).isNotNull();
        assertThat(body.read("$.email", String.class)).isEqualTo(FAY);
        assertThat(body.read("$.role", String.class)).isEqualTo("MEMBER");
        assertThat(body.read("$.status", String.class)).isEqualTo("PENDING");
        Instant createdAt = Instant.parse(body.read("$.createdAt", String.class));
        Instant expiresAt = Instant.parse(body.read("$.expiresAt", String.class));
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofDays(7));
        String token = body.read("$.token", String.class);
        assertThat(token).hasSizeGreaterThanOrEqualTo(32).matches("^[A-Za-z0-9_-]+$");
        assertThat(output.getAll()).doesNotContain(token);
    }

    @ParameterizedTest(name = "AC-5.2 {0} invites with role {1}: {2} {3}")
    @CsvSource({
            "ana, OWNER,  201, ''",
            "ana, ADMIN,  201, ''",
            "ben, ADMIN,  201, ''",
            "ben, MEMBER, 201, ''",
            "ben, READER, 201, ''",
            "ben, OWNER,  422, ROLE_CEILING",
            "cai, READER, 403, FORBIDDEN",
            "dan, READER, 403, FORBIDDEN",
            "eve, READER, 403, FORBIDDEN"
    })
    @Tag("AC-5.2")
    void whoMayInviteWithWhichRole(String actor, String role, int status, String code) throws Exception {
        // When
        MvcResult result = invite(actor + "@example.com", "{\"email\":\"fay@example.com\",\"role\":\"" + role + "\"}");

        // Then
        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.role")).isEqualTo(role);
        } else {
            assertProblem(result, status, code, invitations);
            assertThat(invitationRepository.findAll()).isEmpty();
        }
    }

    @Test
    @Tag("AC-5.3")
    @DisplayName("AC-5.3 No invitation is created when every seat is taken")
    void noInvitationIsCreatedWhenEverySeatIsTaken() throws Exception {
        // Given 4 members and 1 pending invitation use all 5 seats
        assertThat(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}").getResponse().getStatus())
                .isEqualTo(201);

        // When
        MvcResult result = invite(BEN, "{\"email\":\"gus@example.com\",\"role\":\"READER\"}");

        // Then
        assertProblem(result, 409, "SEAT_LIMIT_REACHED", invitations);
        assertThat(listedEmails(BEN)).containsExactly(FAY);
    }

    @Test
    @Tag("AC-5.4")
    @DisplayName("AC-5.4 An expired invitation frees its seat")
    void anExpiredInvitationFreesItsSeat() throws Exception {
        // Given
        seedInvitation("old@example.com", OrganizationRole.MEMBER, InvitationStatus.PENDING,
                Instant.now().minus(1, ChronoUnit.HOURS), "inv-old-QA");

        // When
        MvcResult result = invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-5.5")
    @DisplayName("AC-5.5 Inviting an existing member is a conflict")
    void invitingAnExistingMemberIsAConflict() throws Exception {
        // When
        MvcResult result = invite(BEN, "{\"email\":\"Cai@Example.com\",\"role\":\"ADMIN\"}");

        // Then
        assertProblem(result, 409, "ALREADY_MEMBER", invitations);
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    @Tag("AC-5.6")
    @DisplayName("AC-5.6 Inviting an email that already has a pending invitation is a conflict")
    void invitingAnEmailWithAPendingInvitationIsAConflict() throws Exception {
        // Given
        assertThat(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}").getResponse().getStatus())
                .isEqualTo(201);

        // When
        MvcResult result = invite("ana@example.com", "{\"email\":\"fay@example.com\",\"role\":\"ADMIN\"}");

        // Then
        assertProblem(result, 409, "INVITATION_PENDING", invitations);
        assertThat(invitationRepository.findAll()).hasSize(1);
    }

    @ParameterizedTest(name = "AC-5.7 body {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"email\":\"not-an-email\",\"role\":\"MEMBER\"}   | VALIDATION_FAILED",
            "{\"email\":\"fay@example.com\"}                    | VALIDATION_FAILED",
            "{\"email\":\"fay@example.com\",\"role\":\"SUPERUSER\"} | MALFORMED_REQUEST"
    })
    @Tag("AC-5.7")
    void anInvalidInvitationRequestIsRejected(String body, String code) throws Exception {
        // When
        MvcResult result = invite(BEN, body);

        // Then
        assertProblem(result, 400, code, invitations);
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @ParameterizedTest(name = "AC-5.8 {0} lists pending invitations: {1}")
    @CsvSource({"ana, 200", "ben, 200", "cai, 403", "dan, 403", "eve, 403"})
    @Tag("AC-5.8")
    void listingPendingInvitations(String actor, int status) throws Exception {
        // Given
        assertThat(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}").getResponse().getStatus())
                .isEqualTo(201);
        seedInvitation("old@example.com", OrganizationRole.MEMBER, InvitationStatus.PENDING,
                Instant.now().minus(1, ChronoUnit.HOURS), "inv-old-QA");

        // When
        MvcResult result = list(actor + "@example.com");

        // Then
        if (status == 403) {
            assertProblem(result, 403, "FORBIDDEN", invitations);
            return;
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$[*].email", List.class)).containsExactly(FAY);
        List<Map<String, Object>> items = body.read("$");
        assertThat(items).allSatisfy(item -> {
            assertThat(item).doesNotContainKey("token");
            assertThat(item).containsKeys("id", "email", "role", "status", "createdAt", "expiresAt");
            assertThat(item).containsEntry("status", "PENDING");
        });
    }

    @Test
    @Tag("AC-5.9")
    @DisplayName("AC-5.9 Revoking a pending invitation frees the seat (the last step, accepting the revoked token, "
            + "is in InvitationAcceptanceTest, T13)")
    void revokingAPendingInvitationFreesTheSeat() throws Exception {
        // Given 4 members and Fay's pending invitation use all 5 seats
        String id = JsonPath.read(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}")
                .getResponse().getContentAsString(), "$.id");

        // When
        MvcResult result = revoke(BEN, id);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(list(BEN).getResponse().getContentAsString()).isEqualTo("[]");
        assertThat(invitationRepository.findById(UUID.fromString(id))).get()
                .extracting(Invitation::getStatus).isEqualTo(InvitationStatus.REVOKED);
        assertThat(invite(BEN, "{\"email\":\"gus@example.com\",\"role\":\"READER\"}").getResponse().getStatus())
                .as("the seat is free again").isEqualTo(201);
    }

    @ParameterizedTest(name = "AC-5.10 {0} revokes {1}: {2} {3}")
    @CsvSource({
            "cai, I,       403, FORBIDDEN",
            "ben, J,       404, INVITATION_NOT_FOUND",
            "ben, UNKNOWN, 404, INVITATION_NOT_FOUND"
    })
    @Tag("AC-5.10")
    void revokingIsLimitedToAdminsAndToPendingInvitationsOfTheSameOrganization(String actor, String target,
                                                                               int status, String code)
            throws Exception {
        // Given
        String i = JsonPath.read(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}")
                .getResponse().getContentAsString(), "$.id");
        Organization initech = fixture.organization("Initech QA");
        fixture.member(initech, acme.eve(), OrganizationRole.OWNER);
        Invitation j = seedInvitation(initech, acme.eve(), "gus@example.com", OrganizationRole.MEMBER,
                InvitationStatus.PENDING, Instant.now().plus(7, ChronoUnit.DAYS), "inv-initech-QA");
        String id = switch (target) {
            case "I" -> i;
            case "J" -> j.getId().toString();
            default -> AcmeFixture.UNKNOWN_ID.toString();
        };

        // When
        MvcResult result = revoke(actor + "@example.com", id);

        // Then
        assertProblem(result, status, code, invitations + "/" + id);
        assertThat(invitationRepository.findAll()).allSatisfy(invitation ->
                assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING));
    }

    @Test
    @Tag("AC-5.10")
    @DisplayName("AC-5.10 Revoking an invitation that is already revoked is INVITATION_NOT_PENDING (row 4, an "
            + "accepted invitation, is in InvitationAcceptanceTest, T13)")
    void revokingARevokedInvitationIsNotPending() throws Exception {
        // Given
        String id = JsonPath.read(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}")
                .getResponse().getContentAsString(), "$.id");
        assertThat(revoke(BEN, id).getResponse().getStatus()).isEqualTo(204);

        // When
        MvcResult result = revoke(BEN, id);

        // Then
        assertProblem(result, 409, "INVITATION_NOT_PENDING", invitations + "/" + id);
    }

    @Test
    @DisplayName("Q4: revoking a PENDING invitation that has expired is 204 and makes it REVOKED")
    void revokingAnExpiredPendingInvitationSucceeds() throws Exception {
        // Given
        Invitation expired = seedInvitation("old@example.com", OrganizationRole.MEMBER, InvitationStatus.PENDING,
                Instant.now().minus(1, ChronoUnit.HOURS), "inv-old-QA");

        // When
        MvcResult result = revoke(BEN, expired.getId().toString());

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(invitationRepository.findById(expired.getId())).get()
                .extracting(Invitation::getStatus).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    @DisplayName("Q6 invite order: ROLE_CEILING comes before ALREADY_MEMBER")
    void roleCeilingComesBeforeAlreadyMember() throws Exception {
        assertProblem(invite(BEN, "{\"email\":\"cai@example.com\",\"role\":\"OWNER\"}"), 422, "ROLE_CEILING",
                invitations);
    }

    @Test
    @DisplayName("Q6 invite order: ALREADY_MEMBER comes before INVITATION_PENDING")
    void alreadyMemberComesBeforeInvitationPending() throws Exception {
        // Given a pending invitation for an address that also belongs to a member
        seedInvitation("cai@example.com", OrganizationRole.ADMIN, InvitationStatus.PENDING,
                Instant.now().plus(7, ChronoUnit.DAYS), "inv-cai-QA");

        // Then
        assertProblem(invite(BEN, "{\"email\":\"cai@example.com\",\"role\":\"ADMIN\"}"), 409, "ALREADY_MEMBER",
                invitations);
    }

    @Test
    @DisplayName("Q6 invite order: INVITATION_PENDING comes before SEAT_LIMIT_REACHED")
    void invitationPendingComesBeforeSeatLimit() throws Exception {
        // Given every seat is taken, one of them by Fay's pending invitation
        assertThat(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}").getResponse().getStatus())
                .isEqualTo(201);

        // Then
        assertProblem(invite(BEN, "{\"email\":\"fay@example.com\",\"role\":\"READER\"}"), 409, "INVITATION_PENDING",
                invitations);
    }

    @Test
    @DisplayName("Q6 invite order: request validation (400) comes before authorization (403), assumption A3")
    void validationComesBeforeAuthorization() throws Exception {
        assertProblem(invite("eve@example.com", "{\"email\":\"not-an-email\",\"role\":\"MEMBER\"}"), 400,
                "VALIDATION_FAILED", invitations);
    }

    @Test
    @DisplayName("NFR: two simultaneous invitations for the last seat can't both succeed")
    void twoSimultaneousInvitationsForTheLastSeatCantBothSucceed() throws Exception {
        // Given 4 members: one free seat
        String anaToken = tokenOf(mockMvc, "ana@example.com");
        String benToken = tokenOf(mockMvc, BEN);

        // When
        List<MvcResult> results = concurrently(
                () -> inviteAsReader(anaToken, "fay@example.com"),
                () -> inviteAsReader(benToken, "gus@example.com"));

        // Then
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst()
                .orElseThrow();
        assertProblem(loser, 409, "SEAT_LIMIT_REACHED", invitations);
        assertThat(invitationRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("D11: an organization without a seat limit accepts any number of invitations")
    void anOrganizationWithoutASeatLimitIsUnlimited() throws Exception {
        // Given
        Organization stored = organizationRepository.findById(acme.orgId()).orElseThrow();
        stored.setSeatLimit(null);
        organizationRepository.save(stored);

        // Then
        for (String email : List.of("fay@example.com", "gus@example.com", "hal@example.com")) {
            assertThat(invite(BEN, "{\"email\":\"" + email + "\",\"role\":\"READER\"}").getResponse().getStatus())
                    .isEqualTo(201);
        }
    }

    @SafeVarargs
    private static List<MvcResult> concurrently(Callable<MvcResult>... calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = Arrays.stream(calls)
                    .map(call -> executor.submit(() -> {
                        start.await();
                        return call.call();
                    }))
                    .toList();
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

    private MvcResult inviteAsReader(String token, String email) throws Exception {
        return mockMvc.perform(post(invitations).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"READER\"}")).andReturn();
    }

    private MvcResult invite(String actorEmail, String body) throws Exception {
        return mockMvc.perform(post(invitations)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actorEmail)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult list(String actorEmail) throws Exception {
        return mockMvc.perform(get(invitations).header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actorEmail))))
                .andReturn();
    }

    private MvcResult revoke(String actorEmail, String invitationId) throws Exception {
        return mockMvc.perform(delete(invitations + "/" + invitationId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, actorEmail)))).andReturn();
    }

    private List<String> listedEmails(String actorEmail) throws Exception {
        return JsonPath.read(list(actorEmail).getResponse().getContentAsString(), "$[*].email");
    }

    private Invitation seedInvitation(String email, OrganizationRole role, InvitationStatus status,
                                      Instant expiresAt, String token) {
        Organization acmeOrg = organizationRepository.findById(acme.orgId()).orElseThrow();
        return seedInvitation(acmeOrg, acme.ben(), email, role, status, expiresAt, token);
    }

    private Invitation seedInvitation(Organization organization, User invitedBy, String email, OrganizationRole role,
                                      InvitationStatus status, Instant expiresAt, String token) {
        return invitationRepository.save(Invitation.builder()
                .email(email)
                .organization(organization)
                .role(role)
                .invitedBy(invitedBy)
                .token(token)
                .status(status)
                .createdAt(expiresAt.minus(7, ChronoUnit.DAYS))
                .expiresAt(expiresAt)
                .build());
    }
}
