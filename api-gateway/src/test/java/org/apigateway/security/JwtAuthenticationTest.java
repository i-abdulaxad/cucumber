package org.apigateway.security;

import org.apigateway.filter.AccessLogFilter;
import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.StubResponse;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Authentication at the edge (D4, US-2).
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtAuthenticationTest extends GatewayIntegrationTest {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/auth/register",
            "POST, /api/auth/login",
            "POST, /api/auth/invitations/inv-7Kq2/accept"
    })
    @Tag("AC-2.1")
    void publicAuthOperationsAreForwardedWithoutAToken(String method, String path) {
        // When
        HttpResponse<String> response = gateway.send(method, path);

        // Then
        assertThat(AUTH_SERVICE.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(method);
            assertThat(request.path()).isEqualTo(path);
        });
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @Tag("AC-2.2")
    void aProtectedRequestWithAValidTokenIsForwardedWithItsAuthorizationHeaderUnchanged() {
        // Given
        AUTH_SERVICE.respondWith(StubResponse.status(201));
        String authorization = TestTokens.bearerValid();

        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/orgs")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"name\":\"Acme QA\"}")));

        // Then
        assertThat(AUTH_SERVICE.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/orgs");
            assertThat(request.headerValues("Authorization")).containsExactly(authorization);
        });
        assertThat(response.statusCode()).isEqualTo(201);
    }

    static Stream<Arguments> invalidCredentials() {
        return Stream.of(
                Arguments.of("header missing", Optional.empty()),
                Arguments.of("wrong scheme", Optional.of("Basic YW5hOlNlY3JldDEyMyE=")),
                Arguments.of("empty bearer value", Optional.of("Bearer ")),
                Arguments.of("not a JWT", Optional.of("Bearer not-a-jwt")),
                Arguments.of("signed with another secret", Optional.of("Bearer " + TestTokens.signedWithAnotherSecret())),
                Arguments.of("expired", Optional.of("Bearer " + TestTokens.expired())),
                Arguments.of("unsigned", Optional.of("Bearer " + TestTokens.unsigned())),
                Arguments.of("payload altered after signing",
                        Optional.of("Bearer " + TestTokens.withAlteredSubject(TestTokens.FORGED_USER_ID))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCredentials")
    @Tag("AC-2.3")
    void aProtectedRequestWithoutValidCredentialsIsRejectedWith401(String description, Optional<String> authorization) {
        // Given
        HttpRequest.Builder request = gateway.request("/api/orgs").POST(BodyPublishers.noBody());
        authorization.ifPresent(value -> request.header("Authorization", value));

        // When
        HttpResponse<String> response = gateway.send(request);

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-2.4")
    void aSignedUnexpiredTokenWithoutASubjectIsRejected() {
        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/orgs")
                .header("Authorization", "Bearer " + TestTokens.withoutSubject())
                .POST(BodyPublishers.noBody()));

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    static Stream<Arguments> invalidTokensOnAPublicOperation() {
        return Stream.of(
                Arguments.of("not-a-jwt", "not-a-jwt"),
                Arguments.of("expired", TestTokens.expired()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokensOnAPublicOperation")
    @Tag("AC-2.5")
    void publicOperationsIgnoreAnInvalidTokenAndForwardItUnchanged(String description, String token) {
        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/auth/login")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.noBody()));

        // Then
        assertThat(AUTH_SERVICE.requests()).singleElement().satisfies(request ->
                assertThat(request.headerValues("Authorization")).containsExactly("Bearer " + token));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  /api/auth/login",
            "POST, /api/auth/logout",
            "GET,  /api/auth/invitations/inv-7Kq2/accept"
    })
    @Tag("AC-2.6")
    void onlyTheListedAuthOperationsArePublic(String method, String path) {
        // When
        HttpResponse<String> response = gateway.send(method, path);

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-2.7")
    void a401FromTheGatewayTellsTheClientToUseABearerToken() {
        // When
        HttpResponse<String> response = gateway.send("POST", "/api/orgs");

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                challenge -> assertThat(challenge).startsWith("Bearer"));
    }

    static Stream<Arguments> twoAuthorizationHeaders() {
        String valid = "Bearer " + TestTokens.valid();
        String expired = "Bearer " + TestTokens.expired();
        return Stream.of(
                Arguments.of("valid, then expired", valid, expired),
                Arguments.of("expired, then valid", expired, valid),
                Arguments.of("valid, then valid", valid, valid));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("twoAuthorizationHeaders")
    @Tag("AC-2.9")
    void aProtectedRequestWithMoreThanOneAuthorizationHeaderIsRejected(String description, String first,
                                                                       String second) {
        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/orgs")
                .header("Authorization", first)
                .header("Authorization", second)
                .POST(BodyPublishers.noBody()));

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    static Stream<Arguments> unsafeSubjects() {
        return Stream.of(
                Arguments.of(1, "CR LF and a header line", "abc\r\nX-Injected: 1", "X-Injected"),
                Arguments.of(2, "LF and a log field", "abc\nrequestId=forged", "requestId=forged"),
                Arguments.of(3, "spaces", "abc service=forged-service", "forged-service"),
                Arguments.of(4, "a non-ASCII character", "usuário-1", "usuário-1"));
    }

    @ParameterizedTest(name = "sub-{0}: {1}")
    @MethodSource("unsafeSubjects")
    @Tag("AC-2.10")
    void aSignedTokenWhoseSubjectIsUnsafeForHeadersOrLogsIsRejected(int n, String description, String subject,
                                                                    String injected, CapturedOutput output) {
        // Given
        String requestId = "sub-" + n;

        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/orgs")
                .header("Authorization", "Bearer " + TestTokens.withSubject(subject))
                .header("X-Request-Id", requestId)
                .POST(BodyPublishers.noBody()));

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
        String entry = theOnlyAccessEntry(output, requestId);
        assertThat(entry).contains(" status=401 ").doesNotContain(" userId=");
        assertThat(output.getAll()).doesNotContain(injected, "Unexpected gateway error");
    }

    @Test
    void anUnknownPathWithoutATokenIsRejectedWith401() {
        // When (A9: authentication is checked before route matching)
        HttpResponse<String> response = gateway.send("GET", "/api/projects/1");

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    /**
     * Waits for the access entry (it is written after the response is sent) and checks that exactly one log line
     * carries the request ID, so the entry was written on a single line.
     */
    private static String theOnlyAccessEntry(CapturedOutput output, String requestId) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !linesWithRequestId(output, requestId).isEmpty());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> linesWithRequestId(output, requestId).size() == 1);
        String entry = linesWithRequestId(output, requestId).get(0) + " ";
        assertThat(entry).contains(AccessLogFilter.LOGGER_NAME);
        return entry;
    }

    private static List<String> linesWithRequestId(CapturedOutput output, String requestId) {
        Pattern requestIdField = Pattern.compile("requestId=" + Pattern.quote(requestId) + "(\\s|$)");
        return output.getAll().lines().filter(line -> requestIdField.matcher(line).find()).toList();
    }
}
