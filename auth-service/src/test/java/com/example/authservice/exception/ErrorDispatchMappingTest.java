package com.example.authservice.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening D5 items 2 and 4 for the error dispatch, on the branches a running server can't reach from the
 * outside: a 503 the container reports keeps its status and gets the D7 code, and an original path that isn't a
 * valid URI reference is left out of {@code instance} rather than replaced by the error path.
 */
class ErrorDispatchMappingTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @Tag("AC-2.1")
    @DisplayName("D5.2: a 503 at the error dispatch stays 503, with code SERVICE_UNAVAILABLE and the D7 detail")
    void a503IsServiceUnavailable() {
        ResponseEntity<ProblemDetail> response =
                handler.handleErrorDispatch(new ErrorDispatchException(503, "/api/orgs", null), errorDispatch());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE")
                .containsEntry("requestId", "ph-auth-1");
        assertThat(problem.getDetail()).isEqualTo("The service is temporarily unavailable. Try again later.");
        assertThat(problem.getInstance()).hasToString("/api/orgs");
    }

    @Test
    @Tag("AC-2.1")
    @DisplayName("D5.4: an original path that isn't a valid URI reference is omitted from instance, never /error")
    void anInvalidOriginalPathIsOmitted() {
        ResponseEntity<ProblemDetail> response =
                handler.handleErrorDispatch(new ErrorDispatchException(400, "/api/orgs/a b", null), errorDispatch());

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "MALFORMED_REQUEST");
        assertThat(problem.getInstance()).isNull();
    }

    private static MockHttpServletRequest errorDispatch() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.addHeader("X-Request-Id", "ph-auth-1");
        return request;
    }
}
