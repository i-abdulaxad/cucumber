package org.reportservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Completes Problems in the one format of D13 (scenario-service's contract C3): {@code type} {@code about:blank}, the
 * reason phrase as {@code title}, the request path as {@code instance}, the {@code code}, and the request's
 * {@code X-Request-Id} as {@code requestId} when it is valid.
 */
public final class Problems {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CODE_PROPERTY = "code";
    public static final String REQUEST_ID_PROPERTY = "requestId";
    public static final String ERRORS_PROPERTY = "errors";

    static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, HttpServletRequest request) {
        return completed(ProblemDetail.forStatusAndDetail(code.status(), detail), code, request);
    }

    public static ProblemDetail completed(ProblemDetail problem, ErrorCode code, HttpServletRequest request) {
        problem.setType(ABOUT_BLANK);
        HttpStatus status = HttpStatus.resolve(problem.getStatus());
        if (status != null) {
            problem.setTitle(status.getReasonPhrase());
        }
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty(CODE_PROPERTY, code.name());
        requestId(request).ifPresent(requestId -> problem.setProperty(REQUEST_ID_PROPERTY, requestId));
        return problem;
    }

    /** The request's {@code X-Request-Id}, when it matches {@code ^[A-Za-z0-9._-]{1,128}$}. */
    public static Optional<String> requestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || !VALID_REQUEST_ID.matcher(requestId).matches()) {
            return Optional.empty();
        }
        return Optional.of(requestId);
    }
}
