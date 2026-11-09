package org.apigateway.filter;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.StubResponse;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Access logging (D7, US-7).
 */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest extends GatewayIntegrationTest {

    private static final String ABSENT = "absent";

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "trace-{0}: {1} {2} {3} -> {5}")
    @CsvSource({
            "1, POST, /api/auth/login, without a token,    200, 200, auth-service, absent",
            "2, POST, /api/orgs,       with a valid token, 201, 201, auth-service, 7f1c2a9e-0000-4000-8000-000000000001",
            "3, POST, /api/orgs,       without a token,    201, 401, absent,       absent"
    })
    @Tag("AC-7.4")
    void theGatewayWritesExactlyOneAccessLogEntryPerRequest(int n, String method, String path, String token,
                                                            int stubStatus, int status, String service, String user,
                                                            CapturedOutput output) {
        // Given
        AUTH_SERVICE.respondWith(StubResponse.status(stubStatus));
        String requestId = "trace-" + n;
        HttpRequest.Builder request = gateway.request(path)
                .header("X-Request-Id", requestId)
                .method(method, BodyPublishers.noBody());
        if (token.equals("with a valid token")) {
            request.header("Authorization", TestTokens.bearerValid());
        }

        // When
        gateway.send(request);

        // Then
        String entry = theOnlyAccessEntry(output, requestId);
        assertThat(entry).contains(" method=" + method + " ", " path=" + path + " ", " status=" + status + " ");
        assertThat(entry).containsPattern(" durationMs=\\d+");
        assertField(entry, "service", service);
        assertField(entry, "userId", user);
    }

    @Test
    @Tag("AC-7.5")
    void secretsNeverAppearInGatewayLogs(CapturedOutput output) {
        // Given
        String token = TestTokens.valid();

        // When
        gateway.send(gateway.request("/api/orgs")
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", "secrets-1")
                .POST(BodyPublishers.noBody()));
        gateway.send(gateway.request("/api/auth/login")
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "secrets-2")
                .POST(BodyPublishers.ofString("{\"email\":\"ana@example.com\",\"password\":\"Secret123!\"}")));
        gateway.send(gateway.request("/api/auth/invitations/inv-7Kq2SecretToken/accept")
                .header("X-Request-Id", "secrets-3")
                .POST(BodyPublishers.noBody()));

        // Then
        theOnlyAccessEntry(output, "secrets-1");
        theOnlyAccessEntry(output, "secrets-2");
        assertThat(theOnlyAccessEntry(output, "secrets-3")).contains(" path=/api/auth/invitations/***/accept ");
        assertThat(output.getAll()).doesNotContain(token, "Secret123!", "inv-7Kq2SecretToken");
    }

    /**
     * Rows 1–3 are forwarded as the public accept operation, rows 4–5 are rejected by the request firewall. The
     * paths are sent exactly as written: the request URI is not resolved against a base URI, which would normalise it.
     */
    @ParameterizedTest(name = "mask-{0}: POST {1} -> {3}")
    @CsvSource({
            "1, /api/auth/%69nvitations/inv-Enc1Secret/accept, inv-Enc1Secret, 200",
            "2, /api/%61uth/invitations/inv-Enc2Secret/accept, inv-Enc2Secret, 200",
            "3, /api/auth/inv%69tations/inv-Enc3Secret/accept, inv-Enc3Secret, 200",
            "4, /api/auth//invitations/inv-Dbl4Secret/accept,  inv-Dbl4Secret, 400",
            "5, /api/auth/invitations/./inv-Dot5Secret/accept, inv-Dot5Secret, 400"
    })
    @Tag("AC-7.7")
    void invitationTokensAreMaskedWhateverFormThePathTakes(int n, String path, String token, int status,
                                                           CapturedOutput output) {
        // Given
        String requestId = "mask-" + n;

        // When
        gateway.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Request-Id", requestId)
                .POST(BodyPublishers.noBody()));

        // Then
        String entry = theOnlyAccessEntry(output, requestId);
        assertThat(entry).contains(" status=" + status + " ");
        assertThat(entry).containsPattern(" path=\\S*" + Pattern.quote(AccessLogFilter.MASK) + "\\S* ");
        assertThat(output.getAll().lines().filter(line -> line.contains(token)).toList())
                .as("log lines containing the invitation token %s", token)
                .isEmpty();
    }

    @Test
    void queryStringsAreNeverLogged(CapturedOutput output) {
        // When
        gateway.send(gateway.request("/api/scenarios/42?apiKey=do-not-log-me")
                .header("Authorization", TestTokens.bearerValid())
                .header("X-Request-Id", "query-1")
                .GET());

        // Then
        assertThat(theOnlyAccessEntry(output, "query-1")).contains(" path=/api/scenarios/42 ");
        assertThat(output.getAll()).doesNotContain("do-not-log-me");
    }

    @Test
    void theInvitationTokenIsMaskedWhereverItAppearsInThePath() {
        assertThat(AccessLogFilter.maskedPath("/api/auth/invitations/inv-7Kq2/accept"))
                .isEqualTo("/api/auth/invitations/***/accept");
        assertThat(AccessLogFilter.maskedPath("//api/auth/invitations/inv-7Kq2/accept/"))
                .isEqualTo("//api/auth/invitations/***/accept/");
        assertThat(AccessLogFilter.maskedPath("/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa/invitations"))
                .isEqualTo("/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa/invitations");
    }

    @Test
    void theInvitationTokenIsMaskedInEveryPathForm() {
        assertThat(AccessLogFilter.maskedPath("/api/auth/%69nvitations/inv-7Kq2/accept"))
                .isEqualTo("/api/auth/%69nvitations/***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/%61uth/invitations/inv-7Kq2/accept"))
                .isEqualTo("/api/%61uth/invitations/***/accept");
        assertThat(AccessLogFilter.maskedPath("/API/Auth/INVITATIONS/inv-7Kq2/accept"))
                .isEqualTo("/API/Auth/INVITATIONS/***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/auth//invitations/inv-7Kq2/accept"))
                .isEqualTo("/api/auth//invitations/***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/auth/invitations/./inv-7Kq2/accept"))
                .isEqualTo("/api/auth/invitations/./***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/auth/invitations/%2e/inv-7Kq2/accept"))
                .isEqualTo("/api/auth/invitations/%2e/***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/x/../auth/invitations/inv-7Kq2/accept"))
                .isEqualTo("/api/x/../auth/invitations/***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/auth/invitations;v=1/inv-7Kq2/accept"))
                .isEqualTo("/api/auth/invitations;v=1/***/accept");
        assertThat(AccessLogFilter.maskedPath("/api/auth/invitations/inv-7Kq2/../accept"))
                .doesNotContain("inv-7Kq2");
    }

    @Test
    void pathsWithoutAnInvitationTokenAreLoggedAsReceived() {
        assertThat(AccessLogFilter.maskedPath("/api/auth/invitations")).isEqualTo("/api/auth/invitations");
        assertThat(AccessLogFilter.maskedPath("/api/auth/%zz/invitations")).isEqualTo("/api/auth/%zz/invitations");
        assertThat(AccessLogFilter.maskedPath("/api/scenarios/42/")).isEqualTo("/api/scenarios/42/");
    }

    /**
     * Waits for the entry (it is written after the response is sent) and checks that there is only one.
     */
    private static String theOnlyAccessEntry(CapturedOutput output, String requestId) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !accessEntries(output, requestId).isEmpty());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> accessEntries(output, requestId).size() == 1);
        return accessEntries(output, requestId).get(0) + " ";
    }

    private static List<String> accessEntries(CapturedOutput output, String requestId) {
        Pattern entry = Pattern.compile(Pattern.quote(AccessLogFilter.LOGGER_NAME)
                + ".*requestId=" + Pattern.quote(requestId) + "(\\s|$)");
        return output.getOut().lines().filter(line -> entry.matcher(line).find()).toList();
    }

    private static void assertField(String entry, String field, String expected) {
        if (expected.equals(ABSENT)) {
            assertThat(entry).doesNotContain(" " + field + "=");
        } else {
            assertThat(entry).contains(" " + field + "=" + expected + " ");
        }
    }
}
