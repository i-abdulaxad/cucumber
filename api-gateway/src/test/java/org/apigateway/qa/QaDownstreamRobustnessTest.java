package org.apigateway.qa;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.StubResponse;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.apigateway.qa.QaSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * QA robustness checks for downstream failures (D6, US-4) with a 2 s response timeout. scenario-service is a raw
 * socket stub that can send invalid status lines, non-HTTP replies, truncated bodies and slow bodies.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "GATEWAY_RESPONSE_TIMEOUT=2s")
@DisplayName("QA: invalid, truncated and slow downstream responses")
class QaDownstreamRobustnessTest extends GatewayIntegrationTest {

    private static final RawStubServer RAW_SCENARIO_SERVICE = RawStubServer.start();

    @DynamicPropertySource
    static void scenarioServiceIsARawStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.downstream.scenario-service-url", RAW_SCENARIO_SERVICE::baseUrl);
    }

    @BeforeEach
    void resetRawStub() {
        RAW_SCENARIO_SERVICE.reset();
    }

    @AfterAll
    static void stopRawStub() {
        RAW_SCENARIO_SERVICE.close();
    }

    @Test
    @Tag("AC-4.5")
    @DisplayName("AC-4.5 / D6: a reply that is not HTTP at all produces the 502 Problem")
    void aNonHttpReplyIs502() {
        // Given
        RAW_SCENARIO_SERVICE.on("/api/scenarios/garbage", out -> out.write(RawStubServer.ascii("NOT HTTP AT ALL\r\n\r\n")));

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/garbage");

        // Then
        assertProblemResponse(response, 502, "Bad Gateway", "/api/scenarios/garbage");
    }

    /**
     * BUG-api-gateway-7 (variant of AC-4.5): the invalid status code is relayed to the client as-is.
     */
    @Test
    @Tag("BUG-api-gateway-7")
    @DisplayName("BUG-api-gateway-7 (D6, variant of AC-4.5): a reply with an invalid status code (999) produces the 502 Problem")
    void anInvalidStatusCodeIs502() {
        // Given (RFC 9110 section 15: status codes outside 100..599 are invalid)
        RAW_SCENARIO_SERVICE.on("/api/scenarios/invalid-status", out -> out.write(RawStubServer.ascii(
                "HTTP/1.1 999 Weird\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")));

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/invalid-status");

        // Then
        assertProblemResponse(response, 502, "Bad Gateway", "/api/scenarios/invalid-status");
    }

    /**
     * BUG-api-gateway-4 (variant of AC-4.5): the failure while copying the body is answered 500.
     */
    @Test
    @Tag("BUG-api-gateway-4")
    @DisplayName("BUG-api-gateway-4 (D6, variant of AC-4.5): a connection dropped in the middle of the body produces the 502 Problem")
    void aBodyCutOffMidWayIsNotSuccess() {
        // Given: Content-Length 100, 11 bytes sent, then the connection closes
        RAW_SCENARIO_SERVICE.on("/api/scenarios/truncated", out -> out.write(RawStubServer.ascii(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"partial\":")));

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/truncated");

        // Then
        assertProblemResponse(response, 502, "Bad Gateway", "/api/scenarios/truncated");
    }

    /**
     * BUG-api-gateway-4 (variant of AC-4.2). The response headers arrive at once, but the body trickles in for about 4 s; the JDK client's
     * response timeout (2 s here, 10 s by default) cancels the body and the gateway answers 500 "Unexpected gateway error",
     * which D6 reserves for failures inside the gateway.
     */
    @Test
    @Tag("BUG-api-gateway-4")
    @DisplayName("BUG-api-gateway-4 (D6, variant of AC-4.2): a body slower than the response timeout is reported as 504 or 502, never 500")
    void aSlowBodyIsNotAnInternalError(CapturedOutput output) {
        // Given
        RAW_SCENARIO_SERVICE.on("/api/scenarios/slow-body", out -> {
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n"));
            for (int i = 0; i < 4; i++) {
                out.write(RawStubServer.ascii("6\r\npart-" + i + "\r\n"));
                out.flush();
                Thread.sleep(1000);
            }
            out.write(RawStubServer.ascii("0\r\n\r\n"));
        });

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/slow-body");

        // Then
        assertThat(response.statusCode()).as("status for a slow downstream body").isIn(502, 504);
        assertThat(output.getAll()).doesNotContain("Unexpected gateway error");
    }

    /**
     * AC-4.8, GET row. D6 (amended 2026-10-12) allows one replay of a GET or HEAD whose connection closed before any
     * response byte arrived, so at most two attempts. This replaces the former BUG-api-gateway-3 reproduction, which
     * asserted exactly one request; BUG-3 is closed by that decision (D10).
     */
    @Test
    @Tag("AC-4.8")
    @DisplayName("AC-4.8 (GET row): a GET whose connection is dropped produces 502 and reaches the service at most twice")
    void aDroppedGetIsSentAtMostTwice() {
        // Given
        REPORT_SERVICE.respondWith(StubResponse.dropConnection());

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/reports/42");

        // Then
        assertThat(response.statusCode()).isEqualTo(502);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(REPORT_SERVICE.requests()).as("requests received by report-service").hasSizeBetween(1, 2));
    }

    @Test
    @Tag("AC-4.3")
    @DisplayName("AC-4.3 (variant): a POST whose connection is dropped is not retried")
    void aDroppedPostIsNotRetried() {
        // Given
        AUTH_SERVICE.respondWith(StubResponse.dropConnection());

        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/orgs")
                .header("Authorization", TestTokens.bearerValid())
                .POST(BodyPublishers.ofString("{\"name\":\"Acme QA\"}")));

        // Then
        assertThat(response.statusCode()).isEqualTo(502);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(AUTH_SERVICE.requests()).as("requests received by auth-service").hasSize(1));
    }

    private static void assertProblemResponse(HttpResponse<String> response, int status, String title, String instance) {
        assertProblem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body(), status, title, instance);
    }
}
