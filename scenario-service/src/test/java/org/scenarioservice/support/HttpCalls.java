package org.scenarioservice.support;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls a running scenario-service over a real socket (platform-hardening D9): unlike MockMvc, the container's error
 * dispatch runs. The path is sent exactly as given (raw, never normalized), with HTTP/1.1 and no redirect followed.
 * The server must listen on {@code 127.0.0.1} ({@code server.address}): on macOS a wildcard listener can share its
 * random port with another process's loopback listener, which then receives the calls.
 */
public final class HttpCalls {

    /** Things a Problem body must never contain (D5 item 6): internals, stack traces, the server's identity. */
    private static final String[] INTERNAL_MARKERS = {
            "Exception", "at com.example", "at org.", "at java.", "jakarta.", "Tomcat", "Apache", "jdbc:", "SELECT "
    };

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String origin;

    public HttpCalls(int port) {
        this.origin = "http://127.0.0.1:" + port;
    }

    /** Sends the request; {@code headers} are name/value pairs, {@code body} {@code null} for none (JSON otherwise). */
    public Answer send(String method, String rawPath, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + rawPath))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.headers().firstValue("Content-Type"),
                response.headers().firstValue("WWW-Authenticate"), response.headers().firstValue("Retry-After"),
                response.body());
    }

    /** The parts of a response the tests check. */
    public record Answer(int status, Optional<String> contentType, Optional<String> wwwAuthenticate,
                         Optional<String> retryAfter, String body) {

        /**
         * Asserts a Problem of this service's contract (D16): status, {@code application/problem+json},
         * {@code type} about:blank, the reason phrase as {@code title}, a {@code detail}, {@code code}, and nothing
         * internal. Returns the parsed body for further checks.
         */
        public DocumentContext assertProblem(int expectedStatus, String code) {
            assertThat(status).as("status").isEqualTo(expectedStatus);
            assertThat(contentType).as("Content-Type").hasValue("application/problem+json");
            DocumentContext json = JsonPath.parse(body);
            assertThat(json.read("$.type", String.class)).isEqualTo("about:blank");
            assertThat(json.read("$.title", String.class))
                    .isEqualTo(HttpStatus.valueOf(expectedStatus).getReasonPhrase());
            assertThat(json.read("$.status", Integer.class)).isEqualTo(expectedStatus);
            assertThat(json.read("$.detail", String.class)).isNotBlank();
            assertThat(json.read("$.code", String.class)).isEqualTo(code);
            assertThat(body).doesNotContain(INTERNAL_MARKERS);
            return json;
        }

        /** Asserts that nothing internal (stack trace, exception, server version) is in the body, Problem or not. */
        public void assertNoInternals() {
            assertThat(body).doesNotContain(INTERNAL_MARKERS);
        }
    }
}
