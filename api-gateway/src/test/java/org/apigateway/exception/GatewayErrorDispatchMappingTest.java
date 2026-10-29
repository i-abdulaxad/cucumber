package org.apigateway.exception;

import org.apigateway.filter.RequestIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening D5 item 4 for the gateway's error dispatch, on the branch a running gateway can't reach from the
 * outside: an original path that isn't a valid URI reference is left out of {@code instance} rather than replaced by
 * the error path. The rest of the D6 Problem is unchanged.
 */
class GatewayErrorDispatchMappingTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    @Tag("AC-5.1")
    @DisplayName("D5.4: an original path that isn't a valid URI reference is omitted from instance, never /error")
    void anInvalidOriginalPathIsOmitted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "ph-gw-1");

        ResponseEntity<ProblemDetail> response = handler.handleErrorDispatch(
                new GatewayErrorController.ErrorDispatchException(400, "/api/orgs/a b", null), request);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("The request URL was rejected.");
        assertThat(problem.getProperties()).containsEntry("requestId", "ph-gw-1").doesNotContainKey("code");
        assertThat(problem.getInstance()).isNull();
    }
}
