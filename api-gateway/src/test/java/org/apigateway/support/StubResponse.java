package org.apigateway.support;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scripted answer of a {@link StubServer}: a status, headers and body, optionally sent after a delay.
 * A stub can also accept the request and close the connection without answering.
 */
public record StubResponse(int status, Map<String, String> headers, String body, Duration delay,
                           boolean dropsConnection) {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    public StubResponse {
        headers = Map.copyOf(headers);
    }

    /**
     * The spec's default stub answer: status 200 with body {@code {}}.
     */
    public static StubResponse ok() {
        return json(200, "{}");
    }

    public static StubResponse json(int status, String body) {
        return new StubResponse(status, Map.of(CONTENT_TYPE, APPLICATION_JSON), body, Duration.ZERO, false);
    }

    public static StubResponse status(int status) {
        return new StubResponse(status, Map.of(), "", Duration.ZERO, false);
    }

    /**
     * Accepts the request, then closes the connection without sending a response.
     */
    public static StubResponse dropConnection() {
        return new StubResponse(0, Map.of(), "", Duration.ZERO, true);
    }

    public StubResponse withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new StubResponse(status, copy, body, delay, dropsConnection);
    }

    /**
     * Waits this long after receiving the request before answering.
     */
    public StubResponse after(Duration waitBeforeAnswering) {
        return new StubResponse(status, headers, body, waitBeforeAnswering, dropsConnection);
    }
}
