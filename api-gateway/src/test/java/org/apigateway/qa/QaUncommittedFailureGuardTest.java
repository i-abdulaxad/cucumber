package org.apigateway.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import java.net.http.HttpResponse;

import static org.apigateway.qa.QaSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-hardening US-6, AC-6.3 (D11, gateway FU-4): a failure before the response is committed keeps the D6 502
 * Problem of AC-4.7. Green before T7; it guards the uncommitted path while T7 changes the committed one.
 */
@DisplayName("QA platform-hardening US-6: an uncommitted downstream failure is still the D6 502 Problem")
class QaUncommittedFailureGuardTest extends QaCommittedResponseGatewayTest {

    private static final String PATH = "/api/scenarios/uncommitted-cut-length";

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3: Content-Length 20000, 8,000 bytes sent, then closed: the 502 Problem of AC-4.7, unchanged")
    void anUncommittedFailureIsStillThe502Problem(CapturedOutput output) {
        // Given the stub answers with Content-Length 20000, sends 8,000 bytes (below Tomcat's 8 KB buffer), then closes
        RAW_SCENARIO_SERVICE.on(PATH, out -> {
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 20000\r\nConnection: close\r\n\r\n"));
            out.write(RawStubServer.ascii("x".repeat(8_000)));
        });

        // When ana sends the request with {valid token}
        HttpResponse<String> response = gateway.sendWithValidToken("GET", PATH);

        // Then the response is the 502 Problem of AC-4.7, unchanged
        assertProblem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body(),
                502, "Bad Gateway", PATH);
        assertThat(JsonPath.parse(response.body()).read("$.detail", String.class)).isEqualTo(BAD_RESPONSE_DETAIL);
        assertThat(response.body()).as("none of the downstream bytes reach the client").doesNotContain("xxxx");
        assertThat(RAW_SCENARIO_SERVICE.requestLines()).containsExactly("GET " + PATH + " HTTP/1.1");
        assertThat(output.getAll()).doesNotContain(" ERROR ", "Exception Processing");
    }
}
