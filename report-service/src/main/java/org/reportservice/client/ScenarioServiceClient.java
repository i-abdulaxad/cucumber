package org.reportservice.client;

import org.reportservice.exception.ScenarioServiceUnavailableException;
import org.reportservice.exception.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The scenario-service client (D6, contract C6, scenario-service 1.0.4): its public feature list and a feature's test
 * cases, called directly (never through the gateway) with the caller's bearer token and the request id; the only
 * caller of scenario-service. No cache, no retry (one attempt, RE6), no redirect; connect 1 s, read 5 s
 * ({@code RestClientConfiguration}). Only the members D6 needs are read; unknown members are ignored. A 401 is
 * {@link UnauthenticatedException}; anything else that isn't a readable 200 (no connection, a timeout, a 5xx, 403, 404
 * or other status, an unreadable body) is {@link ScenarioServiceUnavailableException}, with one WARN line naming the
 * failure type only: never the URL, the token or content. Call it before opening a transaction.
 */
@Component
public class ScenarioServiceClient {

    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ScenarioServiceClient.class);
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public ScenarioServiceClient(@Qualifier("scenarioServiceRestClient") RestClient restClient,
                                 JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    /** An active feature, as the list shows it. */
    public record Feature(UUID id, String path, String name, int currentVersion) {
    }

    /** A step of a test case: keyword, text, and its table or doc string content (or null). */
    public record Step(String keyword, String text, List<List<String>> dataTable, String docString) {
    }

    /** A test case of a feature's current version. */
    public record TestCase(UUID id, UUID scenarioId, UUID exampleRowId, String name, List<String> tags, int line,
                           List<Step> steps) {
    }

    /** The project's active features. */
    public List<Feature> activeFeatures(UUID projectId, String token) {
        return list(get("/api/projects/{projectId}/features", token, projectId), this::feature);
    }

    /** The test cases of a feature's current version, in text order. */
    public List<TestCase> testCases(UUID projectId, UUID featureId, String token) {
        return list(get("/api/projects/{projectId}/features/{featureId}/test-cases", token, projectId, featureId),
                this::testCase);
    }

    private JsonNode get(String uri, String token, Object... variables) {
        Answer answer;
        try {
            answer = restClient.get().uri(uri, variables)
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                        String requestId = MDC.get("requestId");
                        if (requestId != null) {
                            headers.set("X-Request-Id", requestId);
                        }
                    })
                    .exchange((request, response) -> {
                        try (InputStream body = response.getBody()) {
                            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                            return new Answer(response.getStatusCode().value(),
                                    bytes.length > MAX_BODY_BYTES ? null : bytes);
                        }
                    });
        } catch (ResourceAccessException ex) {
            throw unavailable(isTimeout(ex) ? "timeout" : "connection failure");
        } catch (RestClientException ex) {
            throw unavailable(ex.getClass().getSimpleName());
        }
        if (answer.status() == 401) {
            throw new UnauthenticatedException();
        }
        if (answer.status() != 200) {
            throw unavailable("status " + answer.status());
        }
        try {
            return answer.body() == null ? null : jsonMapper.readTree(answer.body());
        } catch (JacksonException ex) {
            throw unavailable("unreadable body");
        }
    }

    private <T> List<T> list(JsonNode array, Function<JsonNode, T> item) {
        if (array == null || !array.isArray()) {
            throw unavailable("unreadable body");
        }
        List<T> items = new ArrayList<>(array.size());
        array.forEach(node -> items.add(item.apply(node)));
        return items;
    }

    private Feature feature(JsonNode node) {
        return new Feature(uuid(node, "id"), string(node, "path"), string(node, "name"), integer(node,
                "currentVersion"));
    }

    private TestCase testCase(JsonNode node) {
        JsonNode exampleRow = node.get("exampleRowId");
        UUID exampleRowId = exampleRow == null || exampleRow.isNull() ? null : uuid(node, "exampleRowId");
        List<String> tags = list(node.get("tags"), tag -> {
            if (!tag.isString()) {
                throw unavailable("unreadable body");
            }
            return tag.stringValue();
        });
        return new TestCase(uuid(node, "id"), uuid(node, "scenarioId"), exampleRowId, string(node, "name"), tags,
                integer(node, "line"), list(node.get("steps"), this::step));
    }

    private Step step(JsonNode node) {
        List<List<String>> table = null;
        JsonNode dataTable = node.get("dataTable");
        if (dataTable != null && !dataTable.isNull()) {
            table = list(dataTable, row -> list(row, cell -> {
                if (!cell.isString()) {
                    throw unavailable("unreadable body");
                }
                return cell.stringValue();
            }));
        }
        String docString = null;
        JsonNode doc = node.get("docString");
        if (doc != null && !doc.isNull()) {
            if (!doc.isObject()) {
                throw unavailable("unreadable body");
            }
            docString = string(doc, "content");
        }
        return new Step(string(node, "keyword"), string(node, "text"), table, docString);
    }

    private String string(JsonNode node, String member) {
        JsonNode value = node == null || !node.isObject() ? null : node.get(member);
        if (value == null || !value.isString()) {
            throw unavailable("unreadable body");
        }
        return value.stringValue();
    }

    private int integer(JsonNode node, String member) {
        JsonNode value = node == null || !node.isObject() ? null : node.get(member);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw unavailable("unreadable body");
        }
        return value.intValue();
    }

    private UUID uuid(JsonNode node, String member) {
        String text = string(node, member);
        if (!CANONICAL_UUID.matcher(text).matches()) {
            throw unavailable("unreadable body");
        }
        return UUID.fromString(text);
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static ScenarioServiceUnavailableException unavailable(String reason) {
        log.warn("Scenario service unavailable: {}", reason);
        return new ScenarioServiceUnavailableException();
    }

    private record Answer(int status, byte[] body) {

        @Override
        public String toString() {
            return "Answer[status=" + status + "]";
        }
    }
}
