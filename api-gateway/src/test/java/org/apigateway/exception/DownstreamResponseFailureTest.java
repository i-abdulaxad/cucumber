package org.apigateway.exception;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.RawSocketStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static org.apigateway.exception.ProblemAssertions.assertProblem;
import static org.apigateway.support.RawSocketStub.ascii;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Downstream failures after the response status line and headers arrive (D6, US-4): a body slower than the
 * response timeout, a body cut off before its declared length, and an invalid status code. scenario-service is a
 * raw socket stub, and the response timeout is 2 seconds.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "GATEWAY_RESPONSE_TIMEOUT=2s")
class DownstreamResponseFailureTest extends GatewayIntegrationTest {

    private static final RawSocketStub RAW_SCENARIO_SERVICE = RawSocketStub.start("scenario-service");
    private static final Duration BODY_PART_INTERVAL = Duration.ofSeconds(1);
    private static final int BODY_PARTS = 4;
    private static final String UNEXPECTED_GATEWAY_ERROR = "Unexpected gateway error";

    @DynamicPropertySource
    static void scenarioServiceIsARawSocketStub(DynamicPropertyRegistry registry) {
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
    @Tag("AC-4.6")
    void aResponseBodySlowerThanTheResponseTimeoutProduces504(CapturedOutput output) {
        // Given the stub answers with status 200 and its headers at once, then sends its body in 4 parts 1 second apart
        RAW_SCENARIO_SERVICE.respondWith(out -> {
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n"));
            for (int part = 1; part <= BODY_PARTS; part++) {
                out.write(ascii("6\r\npart-" + part + "\r\n"));
                out.flush();
                Thread.sleep(BODY_PART_INTERVAL.toMillis());
            }
            out.write(ascii("0\r\n\r\n"));
        });

        // When
        long start = System.nanoTime();
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/42");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Then
        assertProblem(response, 504, "Gateway Timeout", "/api/scenarios/42");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
        assertThat(output.getAll()).doesNotContain(UNEXPECTED_GATEWAY_ERROR);
    }

    static Stream<Arguments> incompleteOrInvalidReplies() {
        return Stream.of(
                Arguments.of("status 200, Content-Length 100 and only the first 11 bytes, then the connection closes",
                        (RawSocketStub.Reply) out -> out.write(ascii(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n"
                                        + "{\"partial\":"))),
                Arguments.of("the status line HTTP/1.1 999 Weird, Content-Length 2 and body {}",
                        (RawSocketStub.Reply) out -> out.write(ascii(
                                "HTTP/1.1 999 Weird\r\nContent-Length: 2\r\n\r\n{}"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteOrInvalidReplies")
    @Tag("AC-4.7")
    void anIncompleteOrInvalidDownstreamResponseProduces502(String description, RawSocketStub.Reply reply,
                                                            CapturedOutput output) {
        // Given
        RAW_SCENARIO_SERVICE.respondWith(reply);

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/42");

        // Then
        assertProblem(response, 502, "Bad Gateway", "/api/scenarios/42");
        assertThat(output.getAll()).doesNotContain(UNEXPECTED_GATEWAY_ERROR);
    }

    @Test
    void aFailedDownstreamResponseIsLoggedAtWarnWithoutAStackTraceOrTheDownstreamLocation(CapturedOutput output) {
        // Given a body cut off after the headers arrived
        RAW_SCENARIO_SERVICE.respondWith(out -> out.write(ascii(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"partial\":")));

        // When
        gateway.sendWithValidToken("GET", "/api/scenarios/42");

        // Then
        assertThat(output.getAll()).containsPattern(" WARN .*Downstream call failed: route=scenarios status=502 ");
        assertThat(output.getAll()).doesNotContain(" ERROR ", "\tat ", "Caused by:", RAW_SCENARIO_SERVICE.baseUrl());
    }

    @Test
    void theProblemResponseCarriesNoHeaderFromTheFailedDownstreamResponse() {
        // Given a response whose headers arrive, but whose body is cut off
        RAW_SCENARIO_SERVICE.respondWith(out -> out.write(ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: 100\r\nCache-Control: max-age=60\r\nX-Downstream: partial\r\n\r\n{\"partial\":")));

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/scenarios/42");

        // Then
        assertProblem(response, 502, "Bad Gateway", "/api/scenarios/42");
        assertThat(response.headers().allValues("X-Downstream")).isEmpty();
        assertThat(response.headers().allValues("Cache-Control")).doesNotContain("max-age=60");
        assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
    }
}
