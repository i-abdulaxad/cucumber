package org.reportservice.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The C5 client (contract C4, D5): one {@code GET /internal/projects/{projectId}/access} on auth-service, with the
 * caller's bearer token and the request id. It returns the status and body as received, whatever they are; the
 * {@link ProjectAccessChecker} maps them. No retry (one attempt, RE6), no cache, no redirect. Only the checker uses
 * it.
 */
@Component
public class AccessCheckClient {

    /** C5's answer is a small JSON object; anything larger is not one. */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final RestClient restClient;

    public AccessCheckClient(@Qualifier("authServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** Calls C5; I/O failures and timeouts surface as Spring's {@code RestClientException}s. */
    public Answer fetch(UUID projectId, String token, String requestId) {
        return restClient.get()
                .uri("/internal/projects/{projectId}/access", projectId)
                .headers(headers -> {
                    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    if (requestId != null) {
                        headers.set("X-Request-Id", requestId);
                    }
                })
                .exchange((request, response) -> {
                    try (InputStream body = response.getBody()) {
                        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                        return new Answer(response.getStatusCode().value(),
                                bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8));
                    }
                });
    }

    /** The status and body (null when it is too large) that C5 answered. */
    public record Answer(int status, String body) {

        @Override
        public String toString() {
            return "Answer[status=" + status + "]";
        }
    }
}
