package com.example.authservice.exception;

import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.model.Invitation;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-3 AC-3.2 (D10, E82): another connection's row lock gives a 409 CONFLICT within the (1 s; production 5 s) test lock
 * timeout, with exactly one WARN line from the handler and no ERROR or stack trace, and nothing is stored. (The aspect
 * also logs its phase-2 "Slow call" WARN, because the wait exceeds the 500 ms threshold.) Background: the Acme
 * fixture, and Ben has invited gus@example.com to Acme as MEMBER.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RowLockTimeoutTest {

    private static final Duration WITHIN = Duration.ofSeconds(7);
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\S+\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s.*$");
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s+at\\s+[\\w$.]+\\(");
    private static final String LOCK_WARN = "Request rejected because a lock could not be acquired";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    private Acme acme;
    private String gusToken;
    private UUID gusInvitation;

    @BeforeEach
    void seedAcmeAndInviteGus() throws Exception {
        acme = fixture.seed();
        MvcResult invited = mockMvc.perform(post(invitations())
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ben@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gus@example.com\",\"role\":\"MEMBER\"}"))
                .andReturn();
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        gusToken = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
        gusInvitation = UUID.fromString(JsonPath.read(invited.getResponse().getContentAsString(), "$.id"));
    }

    @Test
    @DisplayName("D10/E82: every connection of the service waits at most 1 s for a lock in tests (5 s in production)")
    void everyTestConnectionHasAOneSecondLockTimeout() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet timeout = statement.executeQuery("SHOW lock_timeout")) {
            timeout.next();
            assertThat(timeout.getString(1)).isEqualTo("1s");
        }
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 Acme's organization row is locked: Ben's invitation of Fay is a 409 CONFLICT")
    void aLockedOrganizationRowIsAConflict(CapturedOutput output) throws Exception {
        // Given
        MockHttpServletRequestBuilder invite = post(invitations())
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, "ben@example.com")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}");

        // When, Then
        whileLocked("SELECT id FROM organizations WHERE id = ? FOR UPDATE", acme.orgId(), invite, invitations(),
                output);
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail).containsExactly("gus@example.com");
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 Gus's invitation row is locked: his anonymous acceptance is a 409 CONFLICT")
    void aLockedInvitationRowIsAConflict(CapturedOutput output) throws Exception {
        // Given
        String accept = "/api/auth/invitations/" + gusToken + "/accept";
        MockHttpServletRequestBuilder request = post(accept)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Secret123!\",\"fullName\":\"Gus Hale\"}");
        long memberships = orgMembershipRepository.count();

        // When, Then
        whileLocked("SELECT id FROM invitations WHERE id = ? FOR UPDATE", gusInvitation, request, accept, output);
        assertThat(userRepository.findByEmail("gus@example.com")).isEmpty();
        assertThat(invitationRepository.findById(gusInvitation).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.PENDING);
        assertThat(orgMembershipRepository.count()).isEqualTo(memberships);
    }

    /**
     * Holds a row lock from another connection while the request runs, then checks the answer and its log.
     */
    private void whileLocked(String lockSql, UUID rowId, MockHttpServletRequestBuilder request, String instance,
                             CapturedOutput output) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(lockSql)) {
                lock.setObject(1, rowId);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).as("the row is locked").isTrue();
                }
            }
            int logStart = output.getAll().length();
            long start = System.nanoTime();

            MvcResult result = mockMvc.perform(request).andReturn();

            Duration took = Duration.ofNanos(System.nanoTime() - start);
            String log = output.getAll().substring(logStart);
            holder.rollback();
            assertThat(took).as("answered within 7 s").isLessThan(WITHIN);
            assertProblem(result, 409, "CONFLICT", instance);
            List<String> entries = entries(log);
            assertThat(entries).as("the handler's WARN line")
                    .filteredOn(entry -> entry.matches("^\\S+\\s+WARN\\s.*") && entry.contains(LOCK_WARN)).hasSize(1);
            assertThat(entries).noneMatch(entry -> entry.matches("^\\S+\\s+ERROR\\s.*"));
            assertThat(STACK_FRAME.matcher(log).find()).as("a stack trace line").isFalse();
        }
    }

    private static List<String> entries(String log) {
        Matcher matcher = ENTRY.matcher(log);
        List<String> entries = new ArrayList<>();
        while (matcher.find()) {
            entries.add(matcher.group());
        }
        return entries;
    }

    private String invitations() {
        return "/api/orgs/" + acme.orgId() + "/invitations";
    }
}
