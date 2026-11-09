package com.example.authservice.service.impl;

import com.example.authservice.dto.request.AcceptInvitationRequest;
import com.example.authservice.exception.InvitationNotFoundException;
import com.example.authservice.exception.RequestValidationException;
import com.example.authservice.exception.RequestValidationException.FieldViolation;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.service.AuthService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of E42's validation step in {@link InvitationServiceImpl#accept}: the service, not the controller, owns
 * the rule that an anonymous caller must supply valid account details (D16). Collaborators the tested paths never
 * reach are left unset ({@code null}).
 */
@ExtendWith(MockitoExtension.class)
class InvitationServiceImplTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final String TOKEN = "inv-unknown-QA";

    @Mock
    private InvitationRepository invitationRepository;
    @Mock
    private OrgMembershipRepository orgMembershipRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthService authService;

    /**
     * The real Bean Validation provider, observable by Mockito.
     */
    @Spy
    private Validator validator = VALIDATOR_FACTORY.getValidator();

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private InvitationServiceImpl service;

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    @Tag("AC-6.10")
    void anAnonymousAcceptanceWithNoBodyReportsFullNameAndPasswordBeforeTheTokenIsLookedUp() {
        // When
        RequestValidationException failure = catchThrowableOfType(RequestValidationException.class,
                () -> service.accept(TOKEN, null, null));

        // Then
        assertThat(failure.violations()).containsExactlyInAnyOrder(
                new FieldViolation("fullName", "Full name cannot be blank"),
                new FieldViolation("password", "Password cannot be blank"));
        verifyNoInteractions(invitationRepository, userRepository, orgMembershipRepository, authService);
    }

    @Test
    @Tag("AC-6.6")
    void anAnonymousAcceptanceWithAnInvalidBodyFailsValidationBeforeTheTokenIsLookedUp() {
        // When
        RequestValidationException failure = catchThrowableOfType(RequestValidationException.class,
                () -> service.accept(TOKEN, null, new AcceptInvitationRequest("short", "Fay Lin")));

        // Then
        assertThat(failure.violations())
                .containsExactly(new FieldViolation("password", "Password must be at least 8 characters long"));
        verifyNoInteractions(invitationRepository, userRepository, orgMembershipRepository, authService);
    }

    @Test
    void aLoggedInCallersInvalidBodyIsIgnored() {
        // Given
        when(invitationRepository.findLockedByToken(TOKEN)).thenReturn(Optional.empty());

        // When
        Throwable failure = catchThrowableOfType(InvitationNotFoundException.class,
                () -> service.accept(TOKEN, UUID.randomUUID(), new AcceptInvitationRequest("x", " ")));

        // Then
        assertThat(failure).as("the token lookup ran, so the body was not rejected").isNotNull();
        verify(invitationRepository).findLockedByToken(TOKEN);
        verifyNoInteractions(validator);
    }
}
