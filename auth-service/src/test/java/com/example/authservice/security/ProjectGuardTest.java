package com.example.authservice.security;

import com.example.authservice.enums.ProjectRole;
import com.example.authservice.enums.UserStatus;
import com.example.authservice.model.User;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E33 (D3, D10): {@code projectGuard} answers "is the caller at least project role R?" for a {@link UserPrincipal}
 * caller, from the database on every call, comparing by rank. Runs against the real bean and H2 with the Projects
 * fixture.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class ProjectGuardTest {

    @Autowired
    private ProjectGuard projectGuard;

    @Autowired
    private ProjectFixture fixture;

    private Projects projects;

    @BeforeEach
    void seedProjects() {
        projects = fixture.seed();
    }

    @Test
    void aUserPrincipalCallerIsAnswered() {
        // When
        boolean allowed = projectGuard.hasRole(projects.checkout(), authenticated(projects.hal()), "TESTER");

        // Then
        assertThat(allowed).isTrue();
    }

    @Test
    void aReaderDoesNotPassAnAdminCheck() {
        // When
        boolean allowed = projectGuard.hasRole(projects.checkout(), authenticated(projects.acme().dan()), "ADMIN");

        // Then
        assertThat(allowed).isFalse();
    }

    @Test
    void aRemovalIsVisibleToTheNextCall() {
        // Given
        Authentication hal = authenticated(projects.hal());
        assertThat(projectGuard.hasRole(projects.checkout(), hal, "READER")).isTrue();

        // When
        fixture.removeMember(projects.checkout(), projects.hal());

        // Then
        assertThat(projectGuard.hasRole(projects.checkout(), hal, "READER")).isFalse();
    }

    /**
     * The fixture's effective-role table (spec glossary) against every required role.
     */
    @ParameterizedTest(name = "{0} in {1} is {2}")
    @CsvSource({
            "ana, checkout, ADMIN", "ana, billing, ADMIN", "ana, portal, NONE",
            "ben, checkout, ADMIN", "ben, billing, ADMIN", "ben, portal, NONE",
            "cai, checkout, ADMIN", "cai, billing, NONE", "cai, portal, NONE",
            "gus, checkout, MEMBER", "gus, billing, NONE", "gus, portal, NONE",
            "hal, checkout, TESTER", "hal, billing, NONE", "hal, portal, NONE",
            "dan, checkout, READER", "dan, billing, NONE", "dan, portal, NONE",
            "ivy, checkout, NONE", "ivy, billing, NONE", "ivy, portal, NONE",
            "eve, checkout, NONE", "eve, billing, NONE", "eve, portal, ADMIN"
    })
    void everyFixtureUserGetsTheirEffectiveRole(String user, String project, String effective) {
        // Given
        Authentication caller = authenticated(user(user));
        UUID projectId = project(project);

        // When / Then
        for (ProjectRole required : ProjectRole.values()) {
            boolean expected = !effective.equals("NONE") && ProjectRole.valueOf(effective).isAtLeast(required);
            assertThat(projectGuard.hasRole(projectId, caller, required.name()))
                    .as("%s at least %s in %s", user, required, project)
                    .isEqualTo(expected);
        }
    }

    @Test
    void callersWithoutAUserPrincipalAreDenied() {
        // Given Ana, implicit ADMIN of Checkout, as principals the filter never sets
        User ana = projects.acme().ana();
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        Authentication otherPrincipal = new UsernamePasswordAuthenticationToken(ana, null, List.of());
        Authentication unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated(
                authenticated(ana).getPrincipal(), null);

        // When / Then
        assertThat(projectGuard.hasRole(projects.checkout(), null, "READER")).isFalse();
        assertThat(projectGuard.hasRole(projects.checkout(), anonymous, "READER")).isFalse();
        assertThat(projectGuard.hasRole(projects.checkout(), otherPrincipal, "READER")).isFalse();
        assertThat(projectGuard.hasRole(projects.checkout(), unauthenticated, "READER")).isFalse();
        assertThat(projectGuard.hasRole(projects.acme().orgId(), projects.checkout(), null, "READER")).isFalse();
    }

    @ParameterizedTest(name = "unknown role \"{0}\" fails closed")
    @NullAndEmptySource
    @ValueSource(strings = {"OWNER", "SUPERUSER", " ADMIN", "4"})
    void anUnknownRoleStringFailsClosed(String role) {
        // Given Ana is implicit ADMIN, so any known role would pass
        Authentication ana = authenticated(projects.acme().ana());

        // When / Then
        assertThat(projectGuard.hasRole(projects.checkout(), ana, role)).isFalse();
        assertThat(projectGuard.hasRole(projects.acme().orgId(), projects.checkout(), ana, role)).isFalse();
    }

    @Test
    void theRoleNameIsCaseInsensitive() {
        assertThat(projectGuard.hasRole(projects.checkout(), authenticated(projects.hal()), "tester")).isTrue();
    }

    @Test
    void anUnknownOrMissingProjectIsDenied() {
        // Given
        Authentication ana = authenticated(projects.acme().ana());

        // When / Then
        assertThat(projectGuard.hasRole(AcmeFixture.UNKNOWN_ID, ana, "READER")).isFalse();
        assertThat(projectGuard.hasRole(null, ana, "READER")).isFalse();
        assertThat(projectGuard.hasRole(projects.acme().orgId(), AcmeFixture.UNKNOWN_ID, ana, "READER")).isFalse();
    }

    @Test
    void aProjectOfAnotherOrganizationIsDeniedUnderThatOrganizationsPath() {
        // Given Eve is OWNER of Initech (implicit ADMIN of Portal); Ana is OWNER of Acme (implicit ADMIN of Checkout)
        Authentication eve = authenticated(projects.acme().eve());
        Authentication ana = authenticated(projects.acme().ana());

        // When / Then
        assertThat(projectGuard.hasRole(projects.initech(), projects.portal(), eve, "ADMIN")).isTrue();
        assertThat(projectGuard.hasRole(projects.acme().orgId(), projects.portal(), eve, "READER")).isFalse();
        assertThat(projectGuard.hasRole(projects.acme().orgId(), projects.checkout(), ana, "ADMIN")).isTrue();
        assertThat(projectGuard.hasRole(projects.initech(), projects.checkout(), ana, "READER")).isFalse();
        assertThat(projectGuard.hasRole(null, projects.checkout(), ana, "READER")).isFalse();
    }

    private User user(String name) {
        return switch (name) {
            case "ana" -> projects.acme().ana();
            case "ben" -> projects.acme().ben();
            case "cai" -> projects.acme().cai();
            case "dan" -> projects.acme().dan();
            case "eve" -> projects.acme().eve();
            case "gus" -> projects.gus();
            case "hal" -> projects.hal();
            case "ivy" -> projects.ivy();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private UUID project(String name) {
        return switch (name) {
            case "checkout" -> projects.checkout();
            case "billing" -> projects.billing();
            case "portal" -> projects.portal();
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Authentication authenticated(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), "hash", List.of(),
                UserStatus.PENDING_VERIFICATION);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
