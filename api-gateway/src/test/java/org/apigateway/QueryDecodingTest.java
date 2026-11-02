package org.apigateway;

import com.jayway.jsonpath.JsonPath;
import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GW6 (report-service spec T17, QA-4): a query whose percent-encoding isn't valid UTF-8 is the gateway's 400 Problem
 * ("Bad Request", detail "The request query could not be decoded.", no {@code code} member), not a 500; the request
 * isn't forwarded, and the log has no ERROR line, no parameter value and at most one WARN line. The existing order is
 * kept: no token on a protected path is still 401, and an unrouted path with a token is still 404.
 */
@ExtendWith(OutputCaptureExtension.class)
class QueryDecodingTest extends GatewayIntegrationTest {

    private static final String PROJECT = "3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13";
    private static final String UPLOAD_TOKEN = "Bearer rct_Q2hlY2tvdXQgdG9rZW4gZXhhbXBsZSBvbmx5IDMyYnl0";
    private static final String DETAIL = "The request query could not be decoded.";

    @ParameterizedTest(name = "GW6 {0} {1}: 400, not forwarded")
    @CsvSource({
            "POST, /api/ci/runs?branch=QaLeakMarker67%FF, upload token",
            "GET,  /api/projects/" + PROJECT + "/runs?page=%FF, valid JWT",
            "GET,  /api/projects/" + PROJECT + "/features?tag=%ED%A0%80, valid JWT",
            "POST, /api/auth/login?x=%E0%A4, none"})
    void aMalformedQueryEncodingIsABadRequest(String method, String pathAndQuery, String credential,
                                              CapturedOutput output) {
        HttpRequest.Builder request = gateway.request(pathAndQuery).header("Content-Type", "application/json")
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("[]")
                        : HttpRequest.BodyPublishers.noBody());
        switch (credential) {
            case "upload token" -> request.header("Authorization", UPLOAD_TOKEN);
            case "valid JWT" -> request.header("Authorization", TestTokens.bearerValid());
            default -> {
            }
        }
        int logStart = output.getAll().length();

        HttpResponse<String> response = gateway.send(request);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        Map<String, Object> problem = JsonPath.read(response.body(), "$");
        assertThat(problem).containsEntry("status", 400).containsEntry("title", "Bad Request")
                .containsEntry("detail", DETAIL).doesNotContainKey("code");
        assertThat(response.body()).doesNotContain("QaLeakMarker67");
        assertNoStubReceivedARequest();
        String log = output.getAll().substring(logStart);
        assertThat(log).doesNotContain("QaLeakMarker67");
        assertThat(log.lines().filter(line -> line.contains(" ERROR "))).isEmpty();
        assertThat(log.lines().filter(line -> line.contains(" WARN ")).count()).isLessThanOrEqualTo(1);
        assertThat(Arrays.stream(log.split("\\R")).filter(line -> line.contains(" WARN ")))
                .allSatisfy(line -> assertThat(line).doesNotContain("%FF", "%ED", "%E0", "Character decoding"));
    }

    @Test
    void withoutATokenAProtectedPathIsStill401() {
        HttpResponse<String> response = gateway.send("GET", "/api/projects/" + PROJECT + "/runs?page=%FF");

        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    @Test
    void anUnroutedPathWithATokenIsStill404() {
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/unknown?page=%FF");

        assertThat(response.statusCode()).isEqualTo(404);
        assertNoStubReceivedARequest();
    }
}
