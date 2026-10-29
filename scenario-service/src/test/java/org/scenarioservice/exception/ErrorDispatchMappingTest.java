package org.scenarioservice.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening D5 item 4 for the error dispatch, on the branch a running server can't reach from the outside:
 * an original path that isn't a valid URI reference is left out of {@code instance} rather than replaced by the error
 * path.
 */
class ErrorDispatchMappingTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @Tag("AC-4.1")
    @DisplayName("D5.4: an original path that isn't a valid URI reference is omitted from instance, never /error")
    void anInvalidOriginalPathIsOmitted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.addHeader("X-Request-Id", "ph-sc-1");

        ResponseEntity<ProblemDetail> response =
                handler.handleErrorDispatch(new ErrorDispatchException(400, "/api/projects/a b", null), request);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "MALFORMED_REQUEST")
                .containsEntry("requestId", "ph-sc-1");
        assertThat(problem.getInstance()).isNull();
    }
}
