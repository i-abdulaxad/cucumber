package org.reportservice.client;

import org.reportservice.configuration.InternalServiceKeyCheck;
import org.reportservice.exception.ProjectCheckUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The C7 client (D5, auth-service contract 1.5.0): one {@code GET /internal/service/projects/{projectId}} with
 * {@code X-Service-Key} and the request id; the only caller of C7. No cache, no retry (one attempt, RE6), no redirect;
 * connect 1 s and read 2 s ({@code RestClientConfiguration}). 200 with exactly the asked {@code projectId} and a
 * boolean {@code archived} is the answer; anything else (no connection, a timeout, a 5xx, 401, 404 or other status,
 * an unreadable body, another project) is {@link ProjectCheckUnavailableException}, with one WARN line naming the
 * failure type only: never the URL, the key or the token. Call it before opening a transaction.
 */
@Component
public class ProjectStatusClient {

    static final int MAX_BODY_BYTES = 16 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ProjectStatusClient.class);
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final RestClient restClient;
    private final InternalServiceKeyCheck serviceKey;
    private final JsonMapper jsonMapper;

    public ProjectStatusClient(@Qualifier("authServiceRestClient") RestClient restClient,
                               InternalServiceKeyCheck serviceKey, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.serviceKey = serviceKey;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Whether the project is archived, as C7 answers it now.
     */
    public boolean isArchived(UUID projectId) {
        Answer answer;
        try {
            answer = restClient.get()
                    .uri("/internal/service/projects/{projectId}", projectId)
                    .headers(headers -> {
                        headers.set("X-Service-Key", serviceKey.key());
                        String requestId = MDC.get("requestId");
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
        } catch (ResourceAccessException ex) {
            throw unavailable(isTimeout(ex) ? "timeout" : "connection failure");
        } catch (RestClientException ex) {
            throw unavailable(ex.getClass().getSimpleName());
        }
        if (answer.status() != 200) {
            throw unavailable("status " + answer.status());
        }
        return archived(projectId, answer.body());
    }

    private boolean archived(UUID projectId, String body) {
        JsonNode status;
        try {
            status = body == null ? null : jsonMapper.readTree(body);
        } catch (JacksonException ex) {
            throw unavailable("unreadable body");
        }
        if (status == null || !status.isObject()) {
            throw unavailable("unreadable body");
        }
        JsonNode answered = status.get("projectId");
        if (answered == null || !answered.isString() || !CANONICAL_UUID.matcher(answered.stringValue()).matches()
                || !UUID.fromString(answered.stringValue()).equals(projectId)) {
            throw unavailable("another projectId");
        }
        JsonNode archived = status.get("archived");
        if (archived == null || !archived.isBoolean()) {
            throw unavailable("no archived flag");
        }
        return archived.booleanValue();
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static ProjectCheckUnavailableException unavailable(String reason) {
        log.warn("Project check unavailable: {}", reason);
        return new ProjectCheckUnavailableException();
    }

    private record Answer(int status, String body) {

        @Override
        public String toString() {
            return "Answer[status=" + status + "]";
        }
    }
}
