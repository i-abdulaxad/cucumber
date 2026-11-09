package com.example.authservice.repository;

import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.enums.Plan;
import com.example.authservice.enums.UserStatus;
import com.example.authservice.model.Invitation;
import com.example.authservice.model.Organization;
import com.example.authservice.model.User;
import com.example.authservice.support.UsesEmptyTestDatabase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E36 (D7): invitations are for organizations only. An invitation needs an organization, and the table has no
 * {@code project_id} column.
 */
@DataJpaTest
@UsesEmptyTestDatabase
class InvitationRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-13T09:30:00Z");

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void anInvitationWithoutAnOrganizationCannotBeSaved() {
        // Given
        Invitation invitation = invitation(null, "inv-no-org");

        // When / Then
        assertThatThrownBy(() -> invitationRepository.saveAndFlush(invitation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anInvitationWithAnOrganizationIsSaved() {
        // Given
        Organization acme = organizationRepository.save(Organization.builder()
                .name("Acme QA").plan(Plan.FREE).seatLimit(5).createdAt(CREATED_AT).build());
        Invitation saved = invitationRepository.save(invitation(acme, "inv-with-org"));
        entityManager.flush();
        entityManager.clear();

        // When
        Invitation loaded = invitationRepository.findById(saved.getId()).orElseThrow();

        // Then
        assertThat(loaded.getOrganization().getId()).isEqualTo(acme.getId());
    }

    @Test
    void theInvitationsTableHasARequiredOrgIdAndNoProjectId() {
        // When
        List<String> columns = jdbcTemplate.queryForList("""
                        SELECT LOWER(COLUMN_NAME) || ' ' || IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE LOWER(TABLE_NAME) = 'invitations' AND LOWER(COLUMN_NAME) IN ('org_id', 'project_id')""",
                String.class);

        // Then
        assertThat(columns).containsExactly("org_id NO");
    }

    private Invitation invitation(Organization organization, String token) {
        User ben = userRepository.save(User.builder()
                .email(token + "@example.com").passwordHash("hash").fullName("Ben Ode")
                .status(UserStatus.ACTIVE).createdAt(CREATED_AT).updatedAt(CREATED_AT).build());
        return Invitation.builder()
                .email("fay@example.com")
                .organization(organization)
                .role(com.example.authservice.enums.OrganizationRole.MEMBER)
                .invitedBy(ben)
                .token(token)
                .status(InvitationStatus.PENDING)
                .createdAt(CREATED_AT)
                .expiresAt(CREATED_AT.plus(7, ChronoUnit.DAYS))
                .build();
    }
}
