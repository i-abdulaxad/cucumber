package com.example.authservice.configuration;

import com.example.authservice.aop.RequestLog;
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
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * report-service FU-1 (as RE8 and AC-7.4 there, user decision 2026-10-29; standards §3 Logging): with the root logger
 * at DEBUG, {@code org.hibernate.orm.core} stays at INFO from {@code application.yaml}, so Hibernate's flush never
 * prints "Listing entities" with whole entity state. The flow writes and flushes users and an invitation: Ben logs in
 * and invites Fay, and Fay accepts as a new account. Log levels are JVM-wide (standards §5), so the root level is set
 * right before the flow and restored in {@code finally}.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RootDebugEntityStateLoggingTest {

    private static final String HIBERNATE_CORE = "org.hibernate.orm.core";
    private static final String FAY = "fay@example.com";
    private static final String BEN = "ben@example.com";
    private static final Pattern HIBERNATE_CORE_DEBUG =
            Pattern.compile("^\\S+\\s+DEBUG\\s.*\\sorg\\.hibernate\\.orm\\.core\\s+:");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoggingSystem loggingSystem;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    @Test
    @Tag("FU-1")
    @DisplayName("FU-1 At root DEBUG, org.hibernate.orm.core stays at INFO and no entity state is logged")
    void rootDebugLogsNoEntityState(CapturedOutput output) throws Exception {
        LoggerConfiguration rootFound = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel rootLevel = rootFound == null ? null : rootFound.getConfiguredLevel();
        LogLevel hibernateCoreLevel;
        String invitationToken;
        int logStart = output.getAll().length();
        try {
            // Given
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
            hibernateCoreLevel = loggingSystem.getLoggerConfiguration(HIBERNATE_CORE).getEffectiveLevel();

            // When
            MvcResult invited = mockMvc.perform(post("/api/orgs/" + acme.orgId() + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(mockMvc, BEN)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + FAY + "\",\"role\":\"MEMBER\"}"))
                    .andReturn();
            assertThat(invited.getResponse().getStatus()).isEqualTo(201);
            invitationToken = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
            int accepted = mockMvc.perform(post("/api/auth/invitations/" + invitationToken + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"" + AcmeFixture.PASSWORD + "\",\"fullName\":\"Fay Lin\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(accepted).isEqualTo(201);
        } finally {
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
        }

        // Then
        assertThat(hibernateCoreLevel).as("effective level of " + HIBERNATE_CORE).isEqualTo(LogLevel.INFO);
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("DEBUG")).as("the flow was logged at DEBUG").isPositive();
        String benHash = userRepository.findById(acme.ben().getId()).orElseThrow().getPasswordHash();
        String fayHash = userRepository.findByEmail(FAY).orElseThrow().getPasswordHash();
        assertThat(log.entries()).as("DEBUG entries of " + HIBERNATE_CORE)
                .noneMatch(line -> HIBERNATE_CORE_DEBUG.matcher(line).find());
        // Entity state (the users' password hashes, Fay's email, the invitation token) is printed only by the flush
        // listing. Emails and the invitation token also reach root DEBUG through Spring's request logging (request
        // bodies and URLs), which FU-1 does not cover, so they are checked only in Hibernate's lines here.
        for (String line : log.lines()) {
            assertThat(line).as("a log line contains entity state")
                    .doesNotContain("Listing entities").doesNotContain(benHash).doesNotContain(fayHash);
            if (line.contains(" " + HIBERNATE_CORE + " ")) {
                assertThat(line.toLowerCase(Locale.ROOT)).as("a Hibernate line contains entity state")
                        .doesNotContain(BEN).doesNotContain(FAY)
                        .doesNotContain(invitationToken.toLowerCase(Locale.ROOT));
            }
        }
    }
}
