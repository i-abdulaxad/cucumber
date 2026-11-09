package org.apigateway.qa;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.RecordedRequest;
import org.apigateway.support.StubResponse;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA checks that requests and responses cross the gateway without rewriting (D2, D6, AC-1.3, AC-1.4), beyond the
 * exact data in the spec's examples.
 */
@DisplayName("QA: request and response fidelity through the gateway")
class QaRequestFidelityTest extends GatewayIntegrationTest {

    /**
     * BUG-api-gateway-5 (variant of AC-1.3): on PUT/PATCH/DELETE the form body is moved into the query string and the body is emptied.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PATCH", "PUT", "DELETE"})
    @Tag("BUG-api-gateway-5")
    @DisplayName("BUG-api-gateway-5 (variant of AC-1.3): a form-encoded body is forwarded as the body, not moved into the query string")
    void formEncodedBodiesStayInTheBody(String method) {
        // When
        gateway.send(gateway.request("/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa?notify=false&page=2")
                .header("Authorization", TestTokens.bearerValid())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, BodyPublishers.ofString("name=Acme+QA")));

        // Then
        RecordedRequest forwarded = AUTH_SERVICE.requests().get(0);
        assertThat(forwarded.query()).isEqualTo("notify=false&page=2");
        assertThat(forwarded.body()).isEqualTo("name=Acme+QA");
    }

    /**
     * BUG-api-gateway-5 (variant of AC-1.3): the exact AC-1.3 request (PATCH, same path, query and JSON body) sent the way {@code curl -d}
     * sends it, with its default form content type. The JSON body ends up URL-encoded in the query string.
     */
    @Test
    @Tag("BUG-api-gateway-5")
    @DisplayName("BUG-api-gateway-5 (variant of AC-1.3): the AC-1.3 PATCH with curl's default form content type keeps its body and query")
    void theAc13RequestWithAFormContentTypeKeepsItsBody() {
        // When
        gateway.send(gateway.request("/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa?notify=false&page=2")
                .header("Authorization", TestTokens.bearerValid())
                .header("Accept-Language", "pt-BR")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method("PATCH", BodyPublishers.ofString("{\"name\":\"Acme QA\"}")));

        // Then
        RecordedRequest forwarded = AUTH_SERVICE.requests().get(0);
        assertThat(forwarded.query()).isEqualTo("notify=false&page=2");
        assertThat(forwarded.body()).isEqualTo("{\"name\":\"Acme QA\"}");
    }

    @Test
    @Tag("AC-1.3")
    @DisplayName("AC-1.3 (variant): a POST form body keeps its content (control for BUG-api-gateway-5)")
    void formEncodedPostBodyKeepsItsContent() {
        // When
        gateway.send(gateway.request("/api/auth/login?x=1")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString("email=ana%40example.com&password=Secret123")));

        // Then: the parameters survive (byte-level differences are reported in the QA report)
        RecordedRequest forwarded = AUTH_SERVICE.requests().get(0);
        assertThat(forwarded.query()).isEqualTo("x=1");
        assertThat(forwarded.body()).contains("email=ana%40example.com", "password=Secret123");
    }

    /**
     * BUG-api-gateway-8 (variant of AC-1.3): the query string is decoded and re-encoded instead of being forwarded as sent.
     */
    @Test
    @Tag("BUG-api-gateway-8")
    @DisplayName("BUG-api-gateway-8 (variant of AC-1.3): the raw query string is forwarded byte for byte")
    @Disabled("BUG-api-gateway-8 deferred: docs/specs/api-gateway.md FU-2")
    void theRawQueryStringIsForwardedUnchanged() {
        // Given only characters RFC 3986 allows unencoded in a query, plus a valueless flag
        String query = "filter=a,b&next=/x/y&at=a:b@c&flag";

        // When
        gateway.send(gateway.request("/api/scenarios/42?" + query).header("Authorization", TestTokens.bearerValid()).GET());

        // Then
        assertThat(SCENARIO_SERVICE.requests().get(0).query()).isEqualTo(query);
    }

    /**
     * BUG-api-gateway-8 (variant of AC-1.3): ";charset=UTF-8" is appended to the client's Content-Type.
     */
    @Test
    @Tag("BUG-api-gateway-8")
    @DisplayName("BUG-api-gateway-8 (variant of AC-1.3): the Content-Type request header is forwarded unchanged")
    @Disabled("BUG-api-gateway-8 deferred: docs/specs/api-gateway.md FU-2")
    void theContentTypeHeaderIsForwardedUnchanged() {
        // When
        gateway.send(gateway.request("/api/orgs")
                .header("Authorization", TestTokens.bearerValid())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"name\":\"Acme QA\"}")));

        // Then
        assertThat(AUTH_SERVICE.requests().get(0).headerValues("Content-Type")).containsExactly("application/json");
    }

    static Stream<Arguments> nonJsonBodies() {
        String largeForm = IntStream.range(0, 4000).mapToObj(i -> "k" + i + "=" + "v".repeat(50))
                .collect(Collectors.joining("&"));
        String boundary = "qa-boundary-7MA4YWxkTrZu0gW";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nAcme QA\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nname=not-a-field&x=1\r\n--" + boundary + "--\r\n";
        return Stream.of(
                Arguments.of("PUT", "application/x-www-form-urlencoded;charset=UTF-8",
                        "city=S\u00e3o+Paulo&name=Acme%20QA&flag&empty="),
                Arguments.of("DELETE", "APPLICATION/X-WWW-FORM-URLENCODED", "name=Acme+QA"),
                Arguments.of("PATCH", "application/x-www-form-urlencoded", largeForm),
                Arguments.of("PUT", "multipart/form-data; boundary=" + boundary, multipart));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nonJsonBodies")
    @Tag("AC-1.7")
    @DisplayName("AC-1.7 (more rows): form bodies with a charset, non-ASCII text, valueless fields, an upper-case media type or 230 KB, and multipart bodies, stay in the body unchanged")
    void nonJsonBodiesStayInTheBodyUnchanged(String method, String contentType, String body) {
        // When
        gateway.send(gateway.request("/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa?notify=false&page=2")
                .header("Authorization", TestTokens.bearerValid())
                .header("Content-Type", contentType)
                .method(method, BodyPublishers.ofString(body)));

        // Then
        assertThat(AUTH_SERVICE.requests()).singleElement().satisfies(forwarded -> {
            assertThat(forwarded.method()).isEqualTo(method);
            assertThat(forwarded.path()).isEqualTo("/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa");
            assertThat(forwarded.query()).isEqualTo("notify=false&page=2");
            assertThat(forwarded.body()).isEqualTo(body);
        });
    }

    @Test
    @Tag("AC-1.3")
    @DisplayName("AC-1.3: repeated and custom request headers, and a non-ASCII JSON body, are forwarded")
    void customHeadersAndUnicodeBodiesAreForwarded() {
        // When
        gateway.send(gateway.request("/api/orgs")
                .header("Authorization", TestTokens.bearerValid())
                .header("X-Custom", "a")
                .header("X-Custom", "b")
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"name\":\"Ação QA ✓\"}")));

        // Then
        RecordedRequest forwarded = AUTH_SERVICE.requests().get(0);
        assertThat(String.join(",", forwarded.headerValues("X-Custom")).replace(" ", "")).isEqualTo("a,b");
        assertThat(forwarded.body()).isEqualTo("{\"name\":\"Ação QA ✓\"}");
    }

    @Test
    @Tag("AC-1.4")
    @DisplayName("AC-1.4: downstream status, repeated headers, Location and body are passed through")
    void responseHeadersArePassedThrough() {
        // Given
        AUTH_SERVICE.respondWith(StubResponse.json(201, "{\"id\":\"9\"}")
                .withHeader("Location", "/api/orgs/9")
                .withHeader("X-Downstream", "kept")
                .withHeader("Cache-Control", "max-age=60"));

        // When
        HttpResponse<String> response = gateway.sendWithValidToken("POST", "/api/orgs");

        // Then
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Location")).containsExactly("/api/orgs/9");
        assertThat(response.headers().allValues("X-Downstream")).containsExactly("kept");
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("max-age=60");
        assertThat(response.body()).isEqualTo("{\"id\":\"9\"}");
    }
}
