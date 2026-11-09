package com.example.authservice.service.impl;

import com.example.authservice.configuration.OrganizationProperties;
import com.example.authservice.dto.request.CreateOrganizationRequest;
import com.example.authservice.dto.request.InviteMemberRequest;
import com.example.authservice.dto.response.CreatedInvitationResponse;
import com.example.authservice.dto.response.InvitationResponse;
import com.example.authservice.dto.response.MemberResponse;
import com.example.authservice.dto.response.OrganizationResponse;
import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.Plan;
import com.example.authservice.exception.AlreadyMemberException;
import com.example.authservice.exception.InvitationNotFoundException;
import com.example.authservice.exception.InvitationNotPendingException;
import com.example.authservice.exception.InvitationPendingException;
import com.example.authservice.exception.LastOwnerException;
import com.example.authservice.exception.MemberNotFoundException;
import com.example.authservice.exception.OrgRoleCeilingException;
import com.example.authservice.exception.SeatLimitReachedException;
import com.example.authservice.exception.SelfRoleChangeException;
import com.example.authservice.model.Invitation;
import com.example.authservice.model.OrgMembership;
import com.example.authservice.model.Organization;
import com.example.authservice.model.User;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.service.OrganizationService;
import com.example.authservice.util.EmailNormalizer;
import com.example.authservice.util.TokenGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationServiceImpl.class);
    private static final int INVITATION_VALIDITY_DAYS = 7;
    private static final String ROLE_CEILING_DETAIL = "You can't grant, change or remove a role above your own.";

    private final OrganizationRepository organizationRepository;
    private final OrgMembershipRepository orgMembershipRepository;
    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final TokenGenerator tokenGenerator;
    private final OrganizationProperties organizationProperties;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final Clock clock;

    @Override
    @Transactional
    public OrganizationResponse createOrganization(CreateOrganizationRequest request, UUID ownerId) {
        Instant now = Instant.now(clock);
        Organization org = new Organization();
        org.setName(request.name());
        org.setPlan(Plan.FREE);
        org.setSeatLimit(organizationProperties.freeSeatLimit());
        org.setCreatedAt(now);
        Organization savedOrg = organizationRepository.save(org);

        OrgMembership membership = new OrgMembership();
        membership.setOrganization(savedOrg);
        membership.setUser(userRepository.getReferenceById(ownerId));
        membership.setRole(OrganizationRole.OWNER);
        membership.setCreatedAt(now);
        orgMembershipRepository.save(membership);

        log.info("Organization {} created by user {}", savedOrg.getId(), ownerId);
        return toResponse(savedOrg, OrganizationRole.OWNER);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationResponse> findByUser(UUID userId) {
        return orgMembershipRepository.findByUserIdOrderByOrganizationNameAscOrganizationIdAsc(userId).stream()
                .map(membership -> toResponse(membership.getOrganization(), membership.getRole()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization(UUID orgId, UUID userId) {
        OrgMembership membership = orgMembershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new AccessDeniedException("Not a member of the organization"));
        return toResponse(membership.getOrganization(), membership.getRole());
    }

    @Override
    @Transactional
    public CreatedInvitationResponse inviteMember(UUID orgId, InviteMemberRequest request, UUID inviterId) {
        Organization organization = lockOrganization(orgId);
        OrganizationRole inviterRole = roleOf(inviterId, orgId);
        if (!inviterRole.isAtLeast(request.role())) {
            throw new OrgRoleCeilingException(ROLE_CEILING_DETAIL);
        }
        String email = EmailNormalizer.normalize(request.email());
        Instant now = Instant.now(clock);
        if (orgMembershipRepository.existsByOrganizationIdAndUserEmail(orgId, email)) {
            throw new AlreadyMemberException();
        }
        if (invitationRepository.existsByOrganizationIdAndEmailAndStatusAndExpiresAtAfter(
                orgId, email, InvitationStatus.PENDING, now)) {
            throw new InvitationPendingException();
        }
        if (organization.getSeatLimit() != null && seatsUsed(orgId, now) >= organization.getSeatLimit()) {
            throw new SeatLimitReachedException();
        }

        Invitation invitation = invitationRepository.save(Invitation.builder()
                .email(email)
                .organization(organization)
                .role(request.role())
                .invitedBy(userRepository.getReferenceById(inviterId))
                .token(tokenGenerator.generateNewToken())
                .status(InvitationStatus.PENDING)
                .expiresAt(now.plus(INVITATION_VALIDITY_DAYS, ChronoUnit.DAYS))
                .createdAt(now)
                .build());

        log.info("Invitation {} created in organization {} by user {}", invitation.getId(), orgId, inviterId);
        return new CreatedInvitationResponse(invitation.getId(), invitation.getEmail(), request.role(),
                invitation.getStatus(), invitation.getCreatedAt(), invitation.getExpiresAt(), invitation.getToken());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationResponse> listPendingInvitations(UUID orgId) {
        return invitationRepository.findByOrganizationIdAndStatusAndExpiresAtAfterOrderByCreatedAtAscIdAsc(
                        orgId, InvitationStatus.PENDING, Instant.now(clock)).stream()
                .map(invitation -> new InvitationResponse(invitation.getId(), invitation.getEmail(),
                        invitation.getRole(), invitation.getStatus(),
                        invitation.getCreatedAt(), invitation.getExpiresAt()))
                .toList();
    }

    @Override
    @Transactional
    public void revokeInvitation(UUID orgId, UUID invitationId, UUID actorId) {
        Invitation invitation = invitationRepository.findLockedByIdAndOrganizationId(invitationId, orgId)
                .orElseThrow(InvitationNotFoundException::new);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new InvitationNotPendingException();
        }
        if (!roleOf(actorId, orgId).isAtLeast(invitation.getRole())) {
            throw new OrgRoleCeilingException(ROLE_CEILING_DETAIL);
        }
        invitation.setStatus(InvitationStatus.REVOKED);
        log.info("Invitation {} in organization {} revoked by user {}", invitationId, orgId, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID orgId) {
        return orgMembershipRepository.findByOrganizationIdOrderByUserEmailAsc(orgId).stream()
                .map(OrganizationServiceImpl::toMember)
                .toList();
    }

    @Override
    @Transactional
    public MemberResponse changeMemberRole(UUID orgId, UUID userId, OrganizationRole newRole, UUID actorId) {
        lockOrganization(orgId);
        OrganizationRole actorRole = roleOf(actorId, orgId);
        OrgMembership target = orgMembershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(MemberNotFoundException::new);
        if (userId.equals(actorId)) {
            throw new SelfRoleChangeException();
        }
        requireAdmin(actorRole);
        if (!actorRole.isAtLeast(target.getRole()) || !actorRole.isAtLeast(newRole)) {
            throw new OrgRoleCeilingException(ROLE_CEILING_DETAIL);
        }
        if (target.getRole() != newRole) {
            target.setRole(newRole);
            if (newRole == OrganizationRole.READER) {
                projectMembershipRepository.lowerRolesToReaderInOrganization(userId, orgId);
            }
            log.info("Role of user {} in organization {} changed by user {}", userId, orgId, actorId);
        }
        return toMember(target);
    }

    @Override
    @Transactional
    public void removeMember(UUID orgId, UUID userId, UUID actorId) {
        lockOrganization(orgId);
        OrganizationRole actorRole = roleOf(actorId, orgId);
        OrgMembership target = orgMembershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(MemberNotFoundException::new);
        if (userId.equals(actorId)) {
            if (target.getRole() == OrganizationRole.OWNER
                    && orgMembershipRepository.countByOrganizationIdAndRole(orgId, OrganizationRole.OWNER) <= 1) {
                throw new LastOwnerException();
            }
        } else {
            requireAdmin(actorRole);
            if (!actorRole.isAtLeast(target.getRole())) {
                throw new OrgRoleCeilingException(ROLE_CEILING_DETAIL);
            }
        }
        orgMembershipRepository.delete(target);
        projectMembershipRepository.deleteByUserIdInOrganization(userId, orgId);
        log.info("User {} removed from organization {} by user {}", userId, orgId, actorId);
    }

    private Organization lockOrganization(UUID orgId) {
        return organizationRepository.findLockedById(orgId)
                .orElseThrow(() -> new AccessDeniedException("Not a member of the organization"));
    }

    private OrganizationRole roleOf(UUID userId, UUID orgId) {
        return orgMembershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .map(OrgMembership::getRole)
                .orElseThrow(() -> new AccessDeniedException("Not a member of the organization"));
    }

    private static void requireAdmin(OrganizationRole actorRole) {
        if (!actorRole.isAtLeast(OrganizationRole.ADMIN)) {
            throw new AccessDeniedException("Role too low for this operation");
        }
    }

    private long seatsUsed(UUID orgId, Instant now) {
        return orgMembershipRepository.countByOrganizationId(orgId)
                + invitationRepository.countByOrganizationIdAndStatusAndExpiresAtAfter(
                orgId, InvitationStatus.PENDING, now);
    }

    private static MemberResponse toMember(OrgMembership membership) {
        User user = membership.getUser();
        return new MemberResponse(user.getId(), user.getEmail(), user.getFullName(), membership.getRole(),
                membership.getCreatedAt());
    }

    private static OrganizationResponse toResponse(Organization organization, OrganizationRole myRole) {
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getPlan(),
                organization.getSeatLimit(), organization.getCreatedAt(), myRole);
    }
}
