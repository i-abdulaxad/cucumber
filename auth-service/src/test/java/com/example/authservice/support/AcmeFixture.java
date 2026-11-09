package com.example.authservice.support;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.Plan;
import com.example.authservice.enums.UserStatus;
import com.example.authservice.model.OrgMembership;
import com.example.authservice.model.Organization;
import com.example.authservice.model.User;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

/**
 * Seeds the spec's Acme fixture through repositories: users Ana, Ben, Cai, Dan and Eve (password
 * {@value #PASSWORD}, status {@code PENDING_VERIFICATION}), and organization "Acme QA" (plan FREE, seat limit 5)
 * with Ana OWNER, Ben ADMIN, Cai MEMBER and Dan READER. Eve belongs to no organization.
 */
@RequiredArgsConstructor
public class AcmeFixture {

    public static final String PASSWORD = "Secret123!";
    public static final String ACME_NAME = "Acme QA";
    public static final int FREE_SEAT_LIMIT = 5;
    /**
     * {unknown id}: matches no row.
     */
    public static final UUID UNKNOWN_ID = UUID.fromString("3b9d5c1e-0000-4000-8000-0000000000ff");

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMembershipRepository orgMembershipRepository;
    private final InvitationRepository invitationRepository;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final ProjectRepository projectRepository;
    private final PasswordEncoder passwordEncoder;

    private String passwordHash;

    /**
     * Deletes every row, children first.
     */
    public void reset() {
        invitationRepository.deleteAllInBatch();
        projectMembershipRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        orgMembershipRepository.deleteAllInBatch();
        organizationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    /**
     * Resets the database and seeds the Acme fixture.
     */
    public Acme seed() {
        reset();
        User ana = user("ana@example.com", "Ana Lima");
        User ben = user("ben@example.com", "Ben Ode");
        User cai = user("cai@example.com", "Cai Wu");
        User dan = user("dan@example.com", "Dan Roe");
        User eve = user("eve@example.com", "Eve Park");
        Organization acme = organization(ACME_NAME);
        member(acme, ana, OrganizationRole.OWNER);
        member(acme, ben, OrganizationRole.ADMIN);
        member(acme, cai, OrganizationRole.MEMBER);
        member(acme, dan, OrganizationRole.READER);
        return new Acme(acme.getId(), ana, ben, cai, dan, eve);
    }

    public User user(String email, String fullName) {
        Instant now = Instant.now();
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordHash())
                .fullName(fullName)
                .status(UserStatus.PENDING_VERIFICATION)
                .mfaEnabled(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    public Organization organization(String name) {
        return organizationRepository.save(Organization.builder()
                .name(name)
                .plan(Plan.FREE)
                .seatLimit(FREE_SEAT_LIMIT)
                .createdAt(Instant.now())
                .build());
    }

    public OrgMembership member(Organization organization, User user, OrganizationRole role) {
        OrgMembership membership = new OrgMembership();
        membership.setOrganization(organization);
        membership.setUser(user);
        membership.setRole(role);
        membership.setCreatedAt(Instant.now());
        return orgMembershipRepository.save(membership);
    }

    /**
     * Seeds a user's status directly, for states the API can't produce in phase 1 (for example SUSPENDED).
     */
    public void setStatus(User user, UserStatus status) {
        User stored = userRepository.findById(user.getId()).orElseThrow();
        stored.setStatus(status);
        userRepository.save(stored);
    }

    private String passwordHash() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        return passwordHash;
    }

    public record Acme(UUID orgId, User ana, User ben, User cai, User dan, User eve) {
    }
}
