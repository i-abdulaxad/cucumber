package org.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.StubResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request ID assignment and propagation (D7, US-7).
 */
class RequestIdFilterTest extends GatewayIntegrationTest {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Test
    @Tag("AC-7.1")
    void theGatewayAssignsARequestIdWhenTheClientSendsNone() {
        // When
        HttpResponse<String> response = gateway.send("POST", "/api/auth/login");

        // Then
        String requestId = theOnlyRequestId(response);
        assertThat(requestId).matches(UUID_PATTERN);
        assertThat(AUTH_SERVICE.requests()).singleElement()
                .satisfies(request -> assertThat(request.headerValues("X-Request-Id")).containsExactly(requestId));
    }

    @Test
    @Tag("AC-7.2")
    void aWellFormedClientRequestIdIsKept() {
        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/auth/login")
                .header("X-Request-Id", "fe-2026-10-11.abc_123")
                .POST(BodyPublishers.noBody()));

        // Then
        assertThat(response.headers().allValues("X-Request-Id")).containsExactly("fe-2026-10-11.abc_123");
        assertThat(AUTH_SERVICE.requests()).singleElement().satisfies(request ->
                assertThat(request.headerValues("X-Request-Id")).containsExactly("fe-2026-10-11.abc_123"));
    }

    static Stream<Arguments> malformedRequestIds() {
        return Stream.of(
                Arguments.of("a string of 129 \"a\" characters", "a".repeat(129)),
                Arguments.of("spaces", "req id with spaces"),
                Arguments.of("semicolon", "abc;def"),
                Arguments.of("an empty string", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRequestIds")
    @Tag("AC-7.3")
    void aMalformedClientRequestIdIsReplaced(String description, String malformed) {
        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/auth/login")
                .header("X-Request-Id", malformed)
                .POST(BodyPublishers.noBody()));

        // Then
        String requestId = theOnlyRequestId(response);
        assertThat(requestId).matches(UUID_PATTERN);
        assertThat(AUTH_SERVICE.requests()).singleElement()
                .satisfies(request -> assertThat(request.headerValues("X-Request-Id")).containsExactly(requestId));
    }

    /**
     * The JDK stub server sends the scripted {@code x-request-id} as {@code X-request-id}: either way it differs in
     * letter case from the gateway's {@code X-Request-Id}, and the client reads every letter case as the same header.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clientRequestIds")
    @Tag("AC-7.6")
    void theResponseCarriesExactlyOneRequestIdTheGateways(String description, Optional<String> clientRequestId) {
        // Given
        AUTH_SERVICE.respondWith(StubResponse.status(200).withHeader("x-request-id", "downstream-echo"));
        HttpRequest.Builder request = gateway.request("/api/auth/login").POST(BodyPublishers.noBody());
        clientRequestId.ifPresent(value -> request.header("X-Request-Id", value));

        // When
        HttpResponse<String> response = gateway.send(request);

        // Then
        String requestId = theOnlyRequestId(response);
        if (clientRequestId.isPresent()) {
            assertThat(requestId).isEqualTo(clientRequestId.get());
        } else {
            assertThat(requestId).matches(UUID_PATTERN);
            assertThat(AUTH_SERVICE.requests()).singleElement().satisfies(forwarded ->
                    assertThat(forwarded.headerValues("X-Request-Id")).containsExactly(requestId));
        }
    }

    static Stream<Arguments> clientRequestIds() {
        return Stream.of(
                Arguments.of("with header X-Request-Id: client-rid", Optional.of("client-rid")),
                Arguments.of("without an X-Request-Id header", Optional.empty()));
    }

    @Test
    void aDownstreamRequestIdIsDroppedInAnyLetterCaseAndNoOtherHeaderIs() {
        // Given
        HttpHeaders downstreamHeaders = new HttpHeaders();
        downstreamHeaders.add("x-request-id", "downstream-lower");
        downstreamHeaders.add("X-REQUEST-ID", "downstream-upper");
        downstreamHeaders.add("X-Request-Id-Echo", "kept");
        downstreamHeaders.add("Content-Type", "application/json");

        // When
        HttpHeaders forwarded = new DownstreamRequestIdHeaderFilter()
                .apply(downstreamHeaders, ServerResponse.ok().build());

        // Then
        assertThat(forwarded.headerNames()).containsExactlyInAnyOrder("X-Request-Id-Echo", "Content-Type");
        assertThat(forwarded.get("X-Request-Id-Echo")).containsExactly("kept");
    }

    @Test
    void theRequestIdIsInTheMdcDuringTheRequestAndRemovedAfterwards() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orgs");
        request.addHeader("X-Request-Id", "trace-mdc");
        AtomicReference<String> seenByChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenByChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        // When
        new RequestIdFilter().doFilter(request, new MockHttpServletResponse(), chain);

        // Then
        assertThat(seenByChain).hasValue("trace-mdc");
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("trace-mdc");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void theRequestIdIsRemovedFromTheMdcWhenTheRequestFails() {
        // Given
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("downstream processing failed");
        };

        // When / Then
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/orgs"), new MockHttpServletResponse(), failingChain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    /**
     * All {@code X-Request-Id} values of the response, in any letter case; there must be exactly one.
     */
    private static String theOnlyRequestId(HttpResponse<String> response) {
        List<String> requestIds = response.headers().allValues("X-Request-Id");
        assertThat(requestIds).as("X-Request-Id values in any letter case").hasSize(1);
        return requestIds.get(0);
    }
}
