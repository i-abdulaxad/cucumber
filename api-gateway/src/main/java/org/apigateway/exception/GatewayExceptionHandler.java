package org.apigateway.exception;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.net.URISyntaxException;

@RestControllerAdvice
public class GatewayExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String GATEWAY_RESPONSE_HEADERS_ATTRIBUTE =
            GatewayExceptionHandler.class.getName() + ".gatewayResponseHeaders";

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure(AuthenticationException ex,
                                                                     HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, GatewayProblems.UNAUTHORIZED_DETAIL, request);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> handleDownstreamFailure(ResourceAccessException ex,
                                                                 HttpServletRequest request,
                                                                 HttpServletResponse response) {
        DownstreamFailure failure = DownstreamFailure.classify(ex);
        log.warn("Downstream call failed: route={} status={} cause={}",
                request.getAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR), failure.status().value(), ioFailureName(ex));
        discardDownstreamResponse(request, response);
        return problem(failure.status(), failure.detail(), request);
    }

    @ExceptionHandler(ServletException.class)
    public ResponseEntity<ProblemDetail> handleWrappedFailure(ServletException ex, HttpServletRequest request,
                                                              HttpServletResponse response) {
        if (ex.getCause() instanceof ResourceAccessException downstreamFailure) {
            return handleDownstreamFailure(downstreamFailure, request, response);
        }
        return handleUnexpectedFailure(ex, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedFailure(Exception ex, HttpServletRequest request) {
        log.error("Unexpected gateway error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, GatewayProblems.INTERNAL_ERROR_DETAIL, request);
    }

    /**
     * Everything that reached the error dispatch (platform-hardening D3, D5), in the D6 format: the status is kept,
     * the detail comes from the D6 and D5 texts, and {@code instance} is the original path, omitted when it isn't a
     * valid URI reference.
     */
    @ExceptionHandler(GatewayErrorController.ErrorDispatchException.class)
    public ResponseEntity<ProblemDetail> handleErrorDispatch(GatewayErrorController.ErrorDispatchException ex,
                                                             HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.status());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = switch (status) {
            case BAD_REQUEST -> GatewayProblems.URL_REJECTED_DETAIL;
            case UNAUTHORIZED -> GatewayProblems.UNAUTHORIZED_DETAIL;
            case NOT_FOUND -> GatewayProblems.NOT_FOUND_DETAIL;
            case METHOD_NOT_ALLOWED -> GatewayProblems.METHOD_NOT_SUPPORTED_DETAIL;
            default -> status.is5xxServerError() ? GatewayProblems.INTERNAL_ERROR_DETAIL : status.getReasonPhrase();
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("Unexpected gateway error", ex.getCause());
        }
        ProblemDetail problem = GatewayProblems.of(status, detail, request);
        problem.setInstance(uriReference(ex.originalPath()));
        return ResponseEntity.status(status).body(problem);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return noRoute(request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return noRoute(request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body == null && statusCode.value() == HttpStatus.METHOD_NOT_ALLOWED.value()
                && ex instanceof ErrorResponse errorResponse) {
            // Spring passes no body for its 405; render it in D6 too (platform-hardening D5, E11)
            ProblemDetail methodNotAllowed = errorResponse.getBody();
            methodNotAllowed.setTitle(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase());
            methodNotAllowed.setDetail(GatewayProblems.METHOD_NOT_SUPPORTED_DETAIL);
            body = methodNotAllowed;
        }
        if (body instanceof ProblemDetail problem && request instanceof ServletWebRequest servletRequest) {
            GatewayProblems.completed(problem, servletRequest.getRequest());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> noRoute(WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ProblemDetail problem = GatewayProblems.of(HttpStatus.NOT_FOUND, GatewayProblems.NOT_FOUND_DETAIL, servletRequest);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    private static @Nullable URI uriReference(@Nullable String path) {
        if (path == null) {
            return null;
        }
        try {
            return new URI(path);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(GatewayProblems.of(status, detail, request));
    }

    private static void discardDownstreamResponse(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(GATEWAY_RESPONSE_HEADERS_ATTRIBUTE) instanceof HttpHeaders gatewayHeaders
                && !response.isCommitted()) {
            response.reset();
            gatewayHeaders.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        }
    }

    private static String ioFailureName(ResourceAccessException failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getClass().getSimpleName();
    }
}
