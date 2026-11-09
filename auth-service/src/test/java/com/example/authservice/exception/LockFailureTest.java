package com.example.authservice.exception;

import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-1.9: a lock that can't be acquired in time is a 409 Problem / CONFLICT, not a 500, and is logged without ERROR
 * and without a stack trace. The four outline rows make taking the organization lock fail with the given exception,
 * thrown from inside {@code EntityManager.find(…, PESSIMISTIC_WRITE)} by a test-only Hibernate statement inspector,
 * so the exception reaches the handler exactly as a real lock failure does. A further test holds the real row lock
 * from another connection.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LockFailureTest {

    private static final String INVITE_FAY = "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}";
    /**
     * Spring Boot's console pattern: timestamp, then the level.
     */
    private static final Pattern ERROR_ENTRY = Pattern.compile("(?m)^\\S+\\s+ERROR\\s");
    /**
     * The handler's single WARN entry for a lock failure.
     */
    private static final Pattern LOCK_WARN_ENTRY =
            Pattern.compile("(?m)^\\S+\\s+WARN\\s.*Request rejected because a lock could not be acquired");
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s+at\\s+[\\w$.]+\\(");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private OrganizationLockFailure organizationLockFailure;

    @Autowired
    private DataSource dataSource;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    @AfterEach
    void stopFailingLocks() {
        organizationLockFailure.clear();
    }

    static Stream<Arguments> lockFailures() {
        return Stream.of(
                Arguments.of("jakarta.persistence.LockTimeoutException",
                        (Supplier<RuntimeException>) () -> new LockTimeoutException("Timeout trying to lock table")),
                Arguments.of("jakarta.persistence.PessimisticLockException",
                        (Supplier<RuntimeException>) () -> new PessimisticLockException("Lock not granted")),
                Arguments.of("org.springframework.dao.PessimisticLockingFailureException",
                        (Supplier<RuntimeException>) () -> new PessimisticLockingFailureException("Lock not granted")),
                Arguments.of("org.springframework.dao.CannotAcquireLockException",
                        (Supplier<RuntimeException>) () -> new CannotAcquireLockException("Lock not acquired")));
    }

    @ParameterizedTest(name = "AC-1.9 the organization lock fails with {0}")
    @MethodSource("lockFailures")
    @Tag("AC-1.9")
    void aLockThatCantBeAcquiredIsAConflict(String exception, Supplier<RuntimeException> failure,
                                            CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ben@example.com");
        organizationLockFailure.failWith(failure);
        int logLengthBefore = output.getAll().length();

        // When
        MvcResult result = inviteFay(token);

        // Then
        assertThat(organizationLockFailure.failures()).as("lock attempts made to fail").isEqualTo(1);
        assertProblem(result, 409, "CONFLICT", invitations());
        assertThat(invitationRepository.findAll()).as("Acme's invitations").isEmpty();
        assertCleanLog(output.getAll().substring(logLengthBefore));
    }

    @Test
    @Tag("AC-1.9")
    @DisplayName("AC-1.9 a real organization row lock held by another connection (jakarta.persistence."
            + "LockTimeoutException from EntityManager.find) is a 409 CONFLICT")
    void aRealLockTimeoutIsAConflict(CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ben@example.com");
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM organizations WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, acme.orgId());
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).as("Acme's row is locked").isTrue();
                }
            }
            int logLengthBefore = output.getAll().length();

            // When
            MvcResult result = inviteFay(token);

            // Then
            assertProblem(result, 409, "CONFLICT", invitations());
            assertCleanLog(output.getAll().substring(logLengthBefore));
            holder.rollback();
        }
        assertThat(invitationRepository.findAll()).as("Acme's invitations").isEmpty();
    }

    private MvcResult inviteFay(String token) throws Exception {
        return mockMvc.perform(post(invitations())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVITE_FAY))
                .andReturn();
    }

    private String invitations() {
        return "/api/orgs/" + acme.orgId() + "/invitations";
    }

    private static void assertCleanLog(String requestLog) {
        assertThat(count(ERROR_ENTRY, requestLog)).as("ERROR entries for the request").isZero();
        assertThat(count(STACK_FRAME, requestLog)).as("stack trace lines for the request").isZero();
        assertThat(count(LOCK_WARN_ENTRY, requestLog)).as("the handler's WARN entry for the request").isEqualTo(1);
    }

    private static int count(Pattern pattern, String log) {
        Matcher matcher = pattern.matcher(log);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Throws the configured exception when Hibernate is about to run the organization lock query
     * ({@code select … from organizations … for update}), so that {@code EntityManager.find} fails like a real lock.
     */
    static class OrganizationLockFailure implements StatementInspector {

        private volatile Supplier<RuntimeException> failure;
        private volatile int failures;

        void failWith(Supplier<RuntimeException> failure) {
            this.failures = 0;
            this.failure = failure;
        }

        void clear() {
            this.failure = null;
        }

        int failures() {
            return failures;
        }

        @Override
        public String inspect(String sql) {
            Supplier<RuntimeException> current = failure;
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (current != null && normalized.contains(" from organizations ") && normalized.contains("for no key update")) {
                failures++;
                throw current.get();
            }
            return sql;
        }
    }

    @TestConfiguration
    static class LockFailureConfiguration {

        @Bean
        OrganizationLockFailure organizationLockFailure() {
            return new OrganizationLockFailure();
        }

        @Bean
        HibernatePropertiesCustomizer organizationLockFailureInspector(OrganizationLockFailure inspector) {
            return properties -> properties.put(JdbcSettings.STATEMENT_INSPECTOR, inspector);
        }
    }
}
