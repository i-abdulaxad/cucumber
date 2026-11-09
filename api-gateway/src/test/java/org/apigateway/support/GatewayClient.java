package org.apigateway.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Plain HTTP client for calling a running gateway the way an external client would.
 */
public final class GatewayClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final URI baseUri;

    public GatewayClient(int port) {
        this.baseUri = URI.create("http://localhost:" + port);
    }

    /**
     * Starts a request to {@code pathAndQuery}; the caller sets method, headers and body.
     */
    public HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(baseUri.resolve(pathAndQuery)).timeout(REQUEST_TIMEOUT);
    }

    public HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the gateway", ex);
        }
    }

    public HttpResponse<String> send(String method, String pathAndQuery) {
        return send(request(pathAndQuery).method(method, HttpRequest.BodyPublishers.noBody()));
    }

    /**
     * Sends a body-less request with {@code Authorization: Bearer {valid token}}.
     */
    public HttpResponse<String> sendWithValidToken(String method, String pathAndQuery) {
        return send(request(pathAndQuery)
                .header("Authorization", TestTokens.bearerValid())
                .method(method, HttpRequest.BodyPublishers.noBody()));
    }
}
