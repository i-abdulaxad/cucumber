package com.example.authservice.exception;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED_DETAIL = "The request has invalid fields.";
    static final String MALFORMED_REQUEST_DETAIL = "The request could not be read.";
    static final String INVALID_CREDENTIALS_DETAIL = "The email or password is incorrect.";
    static final String UNAUTHENTICATED_DETAIL = "A valid bearer token is required.";
    static final String FORBIDDEN_DETAIL = "You are not allowed to perform this operation.";
    static final String NOT_FOUND_DETAIL = "No resource matches this path.";
    static final String EMAIL_ALREADY_REGISTERED_DETAIL = "An account with this email already exists.";
    static final String ROLE_CEILING_DETAIL = "You can't grant, change or remove a role above your own.";
    static final String CONFLICT_DETAIL = "The request conflicts with existing data.";
    static final String LOCK_CONFLICT_DETAIL = "The data is being changed by another request. Try again.";
    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred.";
    static final String SERVICE_UNAVAILABLE_DETAIL = "The service is temporarily unavailable. Try again later.";

    private static final String BEARER_CHALLENGE = "Bearer";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record InvalidField(String field, String message) {
    }

    private static final Comparator<InvalidField> INVALID_FIELD_ORDER =
            Comparator.comparing(InvalidField::field, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                    .thenComparing(InvalidField::message, Comparator.nullsFirst(Comparator.naturalOrder()));

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        return problem(ex.code(), ex.detail(), request);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ProblemDetail> handleRequestValidation(RequestValidationException ex,
                                                                 HttpServletRequest request) {
        List<InvalidField> fields = new ArrayList<>();
        ex.violations().forEach(violation -> fields.add(new InvalidField(violation.field(), violation.message())));
        fields.sort(INVALID_FIELD_ORDER);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorCode.VALIDATION_FAILED.status(),
                VALIDATION_FAILED_DETAIL);
        problem.setProperty(Problems.ERRORS_PROPERTY, fields);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(Problems.completed(problem, ErrorCode.VALIDATION_FAILED, request));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyExists(EmailAlreadyExistsException ex,
                                                                  HttpServletRequest request) {
        return problem(ErrorCode.EMAIL_ALREADY_REGISTERED, EMAIL_ALREADY_REGISTERED_DETAIL, request);
    }

    @ExceptionHandler(OrgRoleCeilingException.class)
    public ResponseEntity<ProblemDetail> handleRoleCeiling(OrgRoleCeilingException ex, HttpServletRequest request) {
        String detail = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ROLE_CEILING_DETAIL;
        return problem(ErrorCode.ROLE_CEILING, detail, request);
    }

    @ExceptionHandler({BadCredentialsException.class, AccountStatusException.class})
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(AuthenticationException ex,
                                                                  HttpServletRequest request) {
        return withBearerChallenge(problem(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL, request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex,
                                                               HttpServletRequest request) {
        return withBearerChallenge(problem(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_DETAIL, request));
    }

    /**
     * An authentication that failed inside the authentication provider (for example while loading the user): 503 when
     * no database connection was available (platform-hardening D7), otherwise 401 as before.
     */
    @ExceptionHandler(InternalAuthenticationServiceException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationServiceFailure(InternalAuthenticationServiceException ex,
                                                                            HttpServletRequest request) {
        return connectionUnavailable(ex)
                .map(failure -> serviceUnavailable(failure, request))
                .orElseGet(() -> handleUnauthenticated(ex, request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(ErrorCode.FORBIDDEN, FORBIDDEN_DETAIL, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                      HttpServletRequest request) {
        log.warn("Request rejected by a database constraint: {}", ex.getClass().getSimpleName());
        return problem(ErrorCode.CONFLICT, CONFLICT_DETAIL, request);
    }

    @ExceptionHandler({LockTimeoutException.class, PessimisticLockException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleLockFailure(RuntimeException ex, HttpServletRequest request) {
        log.warn("Request rejected because a lock could not be acquired: {}", ex.getClass().getSimpleName());
        return problem(ErrorCode.CONFLICT, LOCK_CONFLICT_DETAIL, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        Optional<SQLTransientConnectionException> unavailable = connectionUnavailable(ex);
        if (unavailable.isPresent()) {
            return serviceUnavailable(unavailable.get(), request);
        }
        log.error("Unexpected error while handling a {} request", request.getMethod(), ex);
        return problem(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL, request);
    }

    /**
     * Everything that reached the error dispatch (platform-hardening D3, D5): the status is kept, the code comes from
     * the status table, and {@code instance} is the original path, omitted when it isn't a valid URI reference.
     */
    @ExceptionHandler(ErrorDispatchException.class)
    public ResponseEntity<ProblemDetail> handleErrorDispatch(ErrorDispatchException ex, HttpServletRequest request) {
        HttpStatusCode status = HttpStatusCode.valueOf(ex.status());
        ErrorCode code = status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                ? ErrorCode.SERVICE_UNAVAILABLE : codeFor(ex, status);
        String detail = switch (code) {
            case MALFORMED_REQUEST -> MALFORMED_REQUEST_DETAIL;
            case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE_DETAIL;
            case NOT_FOUND -> NOT_FOUND_DETAIL;
            case INTERNAL_ERROR -> {
                log.error("Unexpected error while handling a {} request", request.getMethod(), ex.getCause());
                yield INTERNAL_ERROR_DETAIL;
            }
            default -> HttpStatus.valueOf(status.value()).getReasonPhrase();
        };
        ProblemDetail problem = Problems.completed(ProblemDetail.forStatusAndDetail(status, detail), code, request);
        problem.setInstance(uriReference(ex.originalPath()));
        return ResponseEntity.status(status).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        return notFound(request);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex, HttpHeaders headers,
                                                                   HttpStatusCode status, WebRequest request) {
        return notFound(request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ErrorCode code = codeFor(ex, statusCode);
        ProblemDetail problem = body instanceof ProblemDetail springProblem
                ? springProblem
                : ProblemDetail.forStatus(statusCode);
        switch (code) {
            case VALIDATION_FAILED -> {
                problem.setDetail(VALIDATION_FAILED_DETAIL);
                problem.setProperty(Problems.ERRORS_PROPERTY, invalidFields(ex));
            }
            case MALFORMED_REQUEST -> problem.setDetail(MALFORMED_REQUEST_DETAIL);
            case INTERNAL_ERROR -> {
                log.error("Unexpected error while handling a {} request", servletRequest.getMethod(), ex);
                problem.setDetail(INTERNAL_ERROR_DETAIL);
            }
            default -> {
                if (!StringUtils.hasText(problem.getDetail())) {
                    problem.setDetail(HttpStatus.valueOf(statusCode.value()).getReasonPhrase());
                }
            }
        }
        Problems.completed(problem, code, servletRequest);
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private static ErrorCode codeFor(Exception ex, HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()
                && (ex instanceof MethodArgumentNotValidException || ex instanceof MethodValidationResult)) {
            return ErrorCode.VALIDATION_FAILED;
        }
        return switch (statusCode.value()) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is4xxClientError() ? ErrorCode.MALFORMED_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static List<InvalidField> invalidFields(Exception ex) {
        List<InvalidField> fields = new ArrayList<>();
        if (ex instanceof MethodArgumentNotValidException notValid) {
            addBindingErrors(notValid.getBindingResult(), fields);
        } else if (ex instanceof MethodValidationResult validation) {
            for (ParameterValidationResult result : validation.getParameterValidationResults()) {
                if (result instanceof ParameterErrors errors) {
                    errors.getFieldErrors().forEach(error -> fields.add(fieldError(error)));
                } else {
                    String parameter = result.getMethodParameter().getParameterName();
                    for (MessageSourceResolvable error : result.getResolvableErrors()) {
                        fields.add(new InvalidField(parameter, error.getDefaultMessage()));
                    }
                }
            }
        }
        fields.sort(INVALID_FIELD_ORDER);
        return fields;
    }

    private static void addBindingErrors(BindingResult bindingResult, List<InvalidField> fields) {
        bindingResult.getFieldErrors().forEach(error -> fields.add(fieldError(error)));
        bindingResult.getGlobalErrors().forEach(error ->
                fields.add(new InvalidField(error.getObjectName(), error.getDefaultMessage())));
    }

    private static InvalidField fieldError(FieldError error) {
        return new InvalidField(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<Object> notFound(WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(ErrorCode.NOT_FOUND, NOT_FOUND_DETAIL, servletRequest));
    }

    /**
     * The pool's "no connection available" failure in the cause chain (platform-hardening D7): HikariCP throws
     * {@link SQLTransientConnectionException} only when it can't hand out a connection. Told apart by type only.
     */
    private static Optional<SQLTransientConnectionException> connectionUnavailable(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException failure) {
                return Optional.of(failure);
            }
        }
        return Optional.empty();
    }

    /** D7: 503 SERVICE_UNAVAILABLE, logged once at WARN with the failure's class name only. */
    private static ResponseEntity<ProblemDetail> serviceUnavailable(SQLTransientConnectionException failure,
                                                                    HttpServletRequest request) {
        log.warn("Request rejected because no database connection is available: {}",
                failure.getClass().getSimpleName());
        return problem(ErrorCode.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_DETAIL, request);
    }

    private static URI uriReference(String path) {
        if (path == null) {
            return null;
        }
        try {
            return new URI(path);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(code.status()).body(Problems.of(code, detail, request));
    }

    private static ResponseEntity<ProblemDetail> withBearerChallenge(ResponseEntity<ProblemDetail> response) {
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
                .body(response.getBody());
    }
}
