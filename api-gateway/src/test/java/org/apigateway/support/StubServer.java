package org.apigateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for a downstream service (D8), built on the JDK's {@code com.sun.net.httpserver}.
 * It listens on an ephemeral loopback port, records every request and answers with a scripted response
 * (by default status 200 with body {@code {}}).
 */
public final class StubServer implements AutoCloseable {

    private final String name;
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile StubResponse response = StubResponse.ok();

    private StubServer(String name, HttpServer server, ExecutorService executor) {
        this.name = name;
        this.server = server;
        this.executor = executor;
    }

    public static StubServer start(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(daemonThreads(name));
            StubServer stub = new StubServer(name, server, executor);
            server.createContext("/", stub::handle);
            server.setExecutor(executor);
            server.start();
            return stub;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not start stub " + name, ex);
        }
    }

    public String name() {
        return name;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port();
    }

    public void respondWith(StubResponse scripted) {
        this.response = scripted;
    }

    /**
     * Forgets recorded requests and restores the default answer.
     */
    public void reset() {
        requests.clear();
        response = StubResponse.ok();
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public int requestCount() {
        return requests.size();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            requests.add(record(exchange));
            StubResponse scripted = response;
            if (!waitBeforeAnswering(scripted.delay()) || scripted.dropsConnection()) {
                // Closing an exchange before its response headers are sent closes the connection.
                return;
            }
            byte[] body = scripted.body().getBytes(StandardCharsets.UTF_8);
            scripted.headers().forEach((headerName, value) -> exchange.getResponseHeaders().add(headerName, value));
            exchange.sendResponseHeaders(scripted.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Stub-side delay used by the timeout scenarios; returns false if the stub is shutting down.
     */
    private static boolean waitBeforeAnswering(Duration delay) {
        if (delay.isZero()) {
            return true;
        }
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static RecordedRequest record(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new RecordedRequest(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
                exchange.getRequestHeaders(), body);
    }

    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "stub-" + name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
