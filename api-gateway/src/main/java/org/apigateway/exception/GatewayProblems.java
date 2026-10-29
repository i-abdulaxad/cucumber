package org.apigateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.apigateway.filter.RequestIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class GatewayProblems {

    public static final String UNAUTHORIZED_DETAIL = "A valid bearer token is required.";
    public static final String NOT_FOUND_DETAIL = "No API route matches this path.";
    public static final String INTERNAL_ERROR_DETAIL = "Unexpected gateway error.";
    /** platform-hardening D5 item 3: a request the firewall rejected (400). */
    public static final String URL_REJECTED_DETAIL = "The request URL was rejected.";
    /** platform-hardening D5 item 3: a method the path doesn't support (405). */
    public static final String METHOD_NOT_SUPPORTED_DETAIL = "This method is not supported for this path.";

    static final URI ABOUT_BLANK = URI.create("about:blank");
    static final String REQUEST_ID_PROPERTY = "requestId";

    private GatewayProblems() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return completed(problem, request);
    }

    public static ProblemDetail completed(ProblemDetail problem, HttpServletRequest request) {
        if (problem.getType() == null) {
            problem.setType(ABOUT_BLANK);
        }
        problem.setInstance(URI.create(request.getRequestURI()));
        if (request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String requestId) {
            problem.setProperty(REQUEST_ID_PROPERTY, requestId);
        }
        return problem;
    }
}
