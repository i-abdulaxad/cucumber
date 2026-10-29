package org.reportservice.exception;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The only place that turns exceptions into Problems (D13), copied from scenario-service: the same shape, the
 * {@code requestId} echo rule, field errors sorted by field, Spring MVC's 400/404/405/406/415 mapped to stable codes,
 * database conflicts and lock timeouts as 409 {@code CONFLICT} (database pattern item 12), every 401 with
 * {@code WWW-Authenticate: Bearer}, and every unexpected failure as
 * {@code INTERNAL_ERROR}, logged once at ERROR with its stack trace. No Problem contains an exception message, a
 * value, SQL or a constraint name.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED_DETAIL = "The request has invalid fields.";
    static final String MALFORMED_REQUEST_DETAIL = "The request could not be read.";
    static final String UNAUTHENTICATED_DETAIL = "A valid bearer token is required.";
    static final String FORBIDDEN_DETAIL = "You are not allowed to perform this operation.";
    static final String NOT_FOUND_DETAIL = "No resource matches this path.";
    static final String CONFLICT_DETAIL = "The request conflicts with existing data.";
    static final String LOCK_CONFLICT_DETAIL = "The data is being changed by another request. Try again.";
    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred.";

    private static final String BEARER_CHALLENGE = "Bearer";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record InvalidField(String field, String message) {
    }

    private static final Comparator<InvalidField> INVALID_FIELD_ORDER =
            Comparator.comparing(InvalidField::field, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                    .thenComparing(InvalidField::message, Comparator.nullsFirst(Comparator.naturalOrder()));

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = problem(ex.code(), ex.detail(), request);
        return ex.code().status() == HttpStatus.UNAUTHORIZED ? withBearerChallenge(response) : response;
    }

    /** A field rule a service checks (for example a past {@code expiresAt}, AC-1.4), as a field error. */
    @ExceptionHandler(InvalidFieldException.class)
    public ResponseEntity<ProblemDetail> handleInvalidField(InvalidFieldException ex, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ex.code(), ex.detail(), request);
        problem.setProperty(Problems.ERRORS_PROPERTY, List.of(new InvalidField(ex.field(), ex.reason())));
        return ResponseEntity.status(ex.code().status()).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex,
                                                               HttpServletRequest request) {
        return withBearerChallenge(problem(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_DETAIL, request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(ErrorCode.FORBIDDEN, FORBIDDEN_DETAIL, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                      HttpServletRequest request) {
        // The message names the constraint and can quote stored content, so only the type is logged
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
        ErrorCode code = codeFor(ex, status);
        String detail = switch (code) {
            case MALFORMED_REQUEST -> MALFORMED_REQUEST_DETAIL;
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
