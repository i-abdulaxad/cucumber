package org.reportservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reportservice.exception.ForbiddenException;
import org.reportservice.exception.ProjectArchivedException;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.ReportsFixture.User;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.reportservice.support.ReportsFixture.IVY;

/**
 * D5, D7, contract C4 (component tests against the auth-service stub): roles compared by rank against each
 * operation's minimum, archived projects (409 after the role check, D13), the forwarded headers, and no database
 * connection held during the call. The caller is put into the security context and the request id into the MDC, as
 * the filters do for a real request.
 */
@ReportServiceIntegrationTest
class ProjectAccessCheckerTest {

    private static final String REQUEST_ID = "req-t3-component";

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private ProjectAccessChecker checker;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetTheStub() {
        stub.reset();
        ReportsFixture.reset();
        MDC.put("requestId", REQUEST_ID);
    }

    @AfterEach
    void clearTheCaller() {
        SecurityContextHolder.clearContext();
        MDC.remove("requestId");
        ReportsFixture.reset();
        stub.reset();
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("C4: one C5 call with the caller's Authorization unchanged and the MDC request id")
    void oneCallForwardsTheTokenAndTheRequestId() {
        as(GUS);

        ProjectAccess access = checker.requireRead(CHECKOUT, ProjectRole.READER);

        assertThat(access).isEqualTo(new ProjectAccess(CHECKOUT, ProjectRole.MEMBER, false));
        assertThat(stub.received()).containsExactly(new AuthServiceStub.Received("GET",
                "/internal/projects/" + CHECKOUT + "/access", GUS.bearer(), REQUEST_ID));
    }

    @Test
    @DisplayName("D7: each operation's minimum role, compared by rank; no access or an unknown project is 403")
    void rolesAreComparedByRank() {
        as(DAN);
        assertThat(checker.requireRead(CHECKOUT, ProjectRole.READER).role()).isEqualTo(ProjectRole.READER);
        assertThat(catchThrowable(() -> checker.requireWrite(CHECKOUT, ProjectRole.TESTER)))
                .isInstanceOf(ForbiddenException.class);
        as(HAL);
        assertThat(checker.requireWrite(CHECKOUT, ProjectRole.TESTER).role()).isEqualTo(ProjectRole.TESTER);
        assertThat(catchThrowable(() -> checker.requireWrite(CHECKOUT, ProjectRole.MEMBER)))
                .isInstanceOf(ForbiddenException.class);
        as(GUS);
        assertThat(checker.requireWrite(CHECKOUT, ProjectRole.MEMBER).role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(catchThrowable(() -> checker.requireRead(CHECKOUT, ProjectRole.ADMIN)))
                .isInstanceOf(ForbiddenException.class);
        as(CAI);
        assertThat(checker.requireWrite(CHECKOUT, ProjectRole.ADMIN).role()).isEqualTo(ProjectRole.ADMIN);
        assertThat(catchThrowable(() -> checker.requireRead(BILLING, ProjectRole.READER)))
                .isInstanceOf(ForbiddenException.class);
        as(IVY);
        assertThat(catchThrowable(() -> checker.requireRead(CHECKOUT, ProjectRole.READER)))
                .isInstanceOf(ForbiddenException.class);
        as(ANA);
        assertThat(catchThrowable(() -> checker.requireRead(UUID.randomUUID(), ProjectRole.READER)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("D7, D13: an archived project allows reads; a write is 409 PROJECT_ARCHIVED, after the role check")
    void anArchivedProjectIsReadOnly() {
        ReportsFixture.archive(CHECKOUT);
        as(DAN);
        assertThat(checker.requireRead(CHECKOUT, ProjectRole.READER).archived()).isTrue();
        assertThat(catchThrowable(() -> checker.requireWrite(CHECKOUT, ProjectRole.TESTER)))
                .isInstanceOf(ForbiddenException.class);
        as(HAL);
        assertThat(catchThrowable(() -> checker.requireWrite(CHECKOUT, ProjectRole.TESTER)))
                .isInstanceOf(ProjectArchivedException.class);
        as(CAI);
        assertThat(checker.requireRead(CHECKOUT, ProjectRole.ADMIN).archived()).isTrue();
    }

    @Test
    @DisplayName("Must not (T3): the check holds no database connection; it succeeds with the pool exhausted")
    void theCheckHoldsNoDatabaseConnection() throws Exception {
        as(GUS);
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                borrowed.add(dataSource.getConnection());
            }
            long start = System.nanoTime();

            assertThatCode(() -> checker.requireWrite(CHECKOUT, ProjectRole.MEMBER)).doesNotThrowAnyException();

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
        assertThat(borrowed).hasSize(12);
    }

    private static void as(User user) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CallerPrincipal(user.id(), user.token()), null, List.of()));
    }
}
