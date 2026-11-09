package com.example.authservice.support;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.ProjectRole;
import com.example.authservice.model.Organization;
import com.example.authservice.model.Project;
import com.example.authservice.model.ProjectMembership;
import com.example.authservice.model.User;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.support.AcmeFixture.Acme;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Seeds the spec's Projects fixture on top of {@link AcmeFixture#seed()} (D15): Acme's seat limit becomes 10;
 * Gus, Hal and Ivy join Acme as MEMBER; project "Checkout" in Acme with explicit members Cai ADMIN, Gus MEMBER,
 * Hal TESTER and Dan READER; project "Billing" in Acme with no explicit members; organization "Initech QA" with Eve
 * OWNER and project "Portal" with no explicit members. All projects are active. Import it next to
 * {@code @AuthServiceIntegrationTest}; {@link AcmeFixture#reset()} already clears projects.
 */
@RequiredArgsConstructor
public class ProjectFixture {

    public static final int ACME_SEAT_LIMIT = 10;

    private final AcmeFixture acmeFixture;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository projectMembershipRepository;

    /**
     * Resets the database and seeds the Projects fixture.
     */
    public Projects seed() {
        Acme acme = acmeFixture.seed();
        Organization acmeOrg = organizationRepository.findById(acme.orgId()).orElseThrow();
        acmeOrg.setSeatLimit(ACME_SEAT_LIMIT);
        organizationRepository.save(acmeOrg);

        User gus = acmeFixture.user("gus@example.com", "Gus Hale");
        User hal = acmeFixture.user("hal@example.com", "Hal Ito");
        User ivy = acmeFixture.user("ivy@example.com", "Ivy Moss");
        acmeFixture.member(acmeOrg, gus, OrganizationRole.MEMBER);
        acmeFixture.member(acmeOrg, hal, OrganizationRole.MEMBER);
        acmeFixture.member(acmeOrg, ivy, OrganizationRole.MEMBER);

        Project checkout = project(acmeOrg, "Checkout");
        member(checkout, acme.cai(), ProjectRole.ADMIN);
        member(checkout, gus, ProjectRole.MEMBER);
        member(checkout, hal, ProjectRole.TESTER);
        member(checkout, acme.dan(), ProjectRole.READER);
        Project billing = project(acmeOrg, "Billing");

        Organization initech = acmeFixture.organization("Initech QA");
        acmeFixture.member(initech, acme.eve(), OrganizationRole.OWNER);
        Project portal = project(initech, "Portal");

        return new Projects(acme, gus, hal, ivy, checkout.getId(), billing.getId(), initech.getId(), portal.getId());
    }

    public Project project(Organization organization, String name) {
        return projectRepository.save(Project.builder()
                .organization(organization)
                .name(name)
                .createdAt(Instant.now())
                .build());
    }

    public ProjectMembership member(Project project, User user, ProjectRole role) {
        return projectMembershipRepository.save(ProjectMembership.builder()
                .project(project)
                .user(user)
                .role(role)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Seeds an archived state directly, for tests that run before the archive operation exists (T12).
     */
    public void archive(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.setArchivedAt(Instant.now());
        projectRepository.save(project);
    }

    /**
     * Deletes an explicit membership directly, for tests that run before the remove operation exists (T13).
     */
    public void removeMember(UUID projectId, User user) {
        projectMembershipRepository.delete(
                projectMembershipRepository.findByUserIdAndProjectId(user.getId(), projectId).orElseThrow());
    }

    public record Projects(Acme acme, User gus, User hal, User ivy, UUID checkout, UUID billing, UUID initech,
                           UUID portal) {
    }
}
