package org.apigateway.support;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls a running gateway over a real socket (platform-hardening D9): unlike MockMvc, the container's error dispatch
 * runs. The path is sent exactly as given (raw, never normalized), with HTTP/1.1 and no redirect followed. The gateway
 * must listen on {@code 127.0.0.1} ({@code SERVER_ADDRESS}): on macOS a wildcard listener can share its random port
 * with another process's loopback listener, which then receives the calls.
 */
public final class HttpCalls {

    /** Things a Problem body must never contain (D5 item 6): internals, stack traces, the server's identity. */
    private static final String[] INTERNAL_MARKERS = {
            "Exception", "at org.", "at java.", "jakarta.", "Tomcat", "Apache", "localhost:"
    };

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final int port;

    public HttpCalls(int port) {
        this.port = port;
    }

    /** Sends the request; {@code headers} are name/value pairs, {@code body} {@code null} for none (JSON otherwise). */
    public Answer send(String method, String rawPath, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
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
                response.headers().firstValue("X-Request-Id"), response.body());
    }

    /**
     * Sends a request line and headers over a plain socket and returns the status code, for methods the JDK client
     * refuses to send ({@code CONNECT}).
     */
    public int sendRaw(String method, String target, String... headers) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n");
            for (int i = 0; i < headers.length; i += 2) {
                head.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder statusLine = new StringBuilder();
            int read;
            while ((read = in.read()) != -1 && read != '\r') {
                statusLine.append((char) read);
            }
            String[] parts = statusLine.toString().split(" ");
            return Integer.parseInt(parts[1]);
        }
    }

    /** The parts of a response the tests check. */
    public record Answer(int status, Optional<String> contentType, Optional<String> requestIdHeader, String body) {

        /**
         * Asserts a Problem of the gateway's D6 format: status, {@code application/problem+json}, {@code type}
         * about:blank, the reason phrase as {@code title}, this {@code detail}, no {@code code}, {@code requestId}
         * equal to the {@code X-Request-Id} response header when that header is present, and nothing internal.
         */
        public DocumentContext assertProblem(int expectedStatus, String detail) {
            assertThat(status).as("status").isEqualTo(expectedStatus);
            assertThat(contentType).as("Content-Type").hasValue("application/problem+json");
            DocumentContext json = JsonPath.parse(body);
            assertThat(json.<Map<String, Object>>read("$")).as("Problem body %s", body)
                    .containsKeys("type", "title", "status", "detail");
            assertThat(json.read("$.type", String.class)).isEqualTo("about:blank");
            assertThat(json.read("$.title", String.class))
                    .isEqualTo(HttpStatus.valueOf(expectedStatus).getReasonPhrase());
            assertThat(json.read("$.status", Integer.class)).isEqualTo(expectedStatus);
            assertThat(json.read("$.detail", String.class)).isEqualTo(detail);
            assertThat(json.<Map<String, Object>>read("$")).as("D6 has no code").doesNotContainKey("code");
            requestIdHeader.ifPresent(header ->
                    assertThat(json.read("$.requestId", String.class)).as("requestId").isEqualTo(header));
            assertThat(body).doesNotContain(INTERNAL_MARKERS);
            return json;
        }
    }
}
