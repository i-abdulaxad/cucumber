package com.example.authservice.exception;

import com.example.authservice.controller.AuthController;
import com.example.authservice.dto.request.AcceptInvitationRequest;
import com.example.authservice.exception.RequestValidationException.FieldViolation;
import com.example.authservice.security.UserPrincipal;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

/**
 * E42: {@link RequestValidationException}, thrown by a service that validates a body itself, is answered with the
 * same Problem as Spring MVC's {@link MethodArgumentNotValidException} for the same constraint violations: status,
 * title, type, {@code detail}, {@code instance}, {@code code}, {@code requestId}, and the {@code errors} entries with
 * the same field names, messages and order.
 */
class ApiExceptionHandlerTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final String ACCEPT_PATH = "/api/auth/invitations/inv-QA/accept";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("{}", new AcceptInvitationRequest(null, null)),
                Arguments.of("an empty password", new AcceptInvitationRequest("", "Fay Lin")),
                Arguments.of("a blank password and an empty name", new AcceptInvitationRequest("       ", "")),
                Arguments.of("a short password", new AcceptInvitationRequest("short", "Fay Lin")),
                Arguments.of("a password over 72 bytes and a name over 100 characters",
                        new AcceptInvitationRequest("é".repeat(40), "n".repeat(101))));
    }

    @ParameterizedTest(name = "AC-6.6 / AC-6.10 {0}")
    @MethodSource("invalidBodies")
    @Tag("AC-6.6")
    @Tag("AC-6.10")
    void aServiceValidationErrorIsTheSameProblemAsSpringMvcs(String body, AcceptInvitationRequest request)
            throws Exception {
        // Given
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", ACCEPT_PATH);
        servletRequest.addHeader(Problems.REQUEST_ID_HEADER, "rid-1");

        // When
        ProblemDetail fromSpring = springProblem(request, servletRequest);
        ResponseEntity<ProblemDetail> fromService = handler.handleRequestValidation(serviceException(request),
                servletRequest);

        // Then
        assertThat(fromService.getStatusCode().value()).isEqualTo(400);
        assertThat(fromService.getHeaders().isEmpty()).as("no extra headers").isTrue();
        ProblemDetail problem = fromService.getBody();
        assertThat(problem).isEqualTo(fromSpring);
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("requestId", "rid-1");
        assertThat(List.copyOf(problem.getProperties().keySet()))
                .isEqualTo(List.copyOf(fromSpring.getProperties().keySet()));
        assertThat(problem.getProperties().get("errors")).asInstanceOf(LIST).isNotEmpty();
    }

    /**
     * What Spring MVC's {@code @Valid @RequestBody} produces for the request, through the same advice.
     */
    private ProblemDetail springProblem(AcceptInvitationRequest request, MockHttpServletRequest servletRequest)
            throws Exception {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(request, "acceptInvitationRequest");
        new SpringValidatorAdapter(VALIDATOR).validate(request, errors);
        MethodParameter body = new MethodParameter(AuthController.class.getMethod("acceptInvitation",
                String.class, AcceptInvitationRequest.class, UserPrincipal.class), 1);
        ResponseEntity<Object> response = handler.handleException(new MethodArgumentNotValidException(body, errors),
                new ServletWebRequest(servletRequest, new MockHttpServletResponse()));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().isEmpty()).as("no extra headers").isTrue();
        return (ProblemDetail) response.getBody();
    }

    /**
     * What the service throws for the same request (the mapping in {@code InvitationServiceImpl}).
     */
    private static RequestValidationException serviceException(AcceptInvitationRequest request) {
        return new RequestValidationException(VALIDATOR.validate(request).stream()
                .map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList());
    }
}
