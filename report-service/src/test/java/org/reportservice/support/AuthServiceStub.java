package org.reportservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The auth-service stub (D16): an in-process HTTP server (JDK {@code HttpServer}, no dependency) on {@code 127.0.0.1}
 * that stands in for auth-service's access check {@code GET /internal/projects/{id}/access} (C5) and answers per the
 * {@link ReportsFixture}. It records every request, and a test can make it fail, wait, answer oddly, change a role or
 * stop listening. One per test JVM, on the port {@link TestSettings} reserved, which every test context's
 * {@code app.auth-service.base-url} points at; binding it fails loudly (no retry). Tests call {@link #reset()} first.
 */
public final class AuthServiceStub {

    private static final Pattern ACCESS_PATH = Pattern.compile("^/internal/projects/([^/]+)/access$");
    private static final Pattern SUBJECT = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"");

    private static AuthServiceStub instance;

    private final int port;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Optional<String>> roleOverrides = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> rejectedUsers = new ConcurrentHashMap<>();
    private volatile Answer fixedAnswer;
    private volatile Duration delay = Duration.ZERO;
    private HttpServer server;

    private AuthServiceStub(int port) {
        this.port = port;
    }

    /** The JVM's stub, started on first use. */
    public static synchronized AuthServiceStub get() {
        if (instance == null) {
            instance = new AuthServiceStub(TestSettings.releaseAuthServicePort());
            instance.start();
        }
        return instance;
    }

    /** Back to the fixture's answers, running, with no recorded request. */
    public synchronized void reset() {
        received.clear();
        roleOverrides.clear();
        rejectedUsers.clear();
        fixedAnswer = null;
        delay = Duration.ZERO;
        if (server == null) {
            start();
        }
    }

    /** Stops listening: nothing accepts connections on the port until {@link #reset()}. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Every request received since the last reset, in order. */
    public List<Received> received() {
        return List.copyOf(received);
    }

    /** Answers every request with this status and body, whatever the fixture says. */
    public void answer(int status, String body) {
        fixedAnswer = new Answer(status, body);
    }

    /** Waits this long before answering. */
    public void delay(Duration wait) {
        delay = wait;
    }

    /** From now on, the user has this role in the project ({@code null} for none: 403). */
    public void role(UUID userId, UUID projectId, String role) {
        roleOverrides.put(userId + "|" + projectId, Optional.ofNullable(role));
    }

    /** From now on, auth-service rejects the user's token (401), for example after a suspension. */
    public void rejectToken(UUID userId) {
        rejectedUsers.put(userId, Boolean.TRUE);
    }

    /** The 200 body for this project and role, as C5 answers it. */
    public static String accessBody(UUID projectId, String role) {
        return "{\"projectId\":\"" + projectId + "\",\"orgId\":\"" + ReportsFixture.ACME + "\",\"role\":\"" + role
                + "\",\"archived\":" + ReportsFixture.archived(projectId) + "}";
    }

    private synchronized void start() {
        try {
            HttpServer created = HttpServer.create();
            created.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
            created.createContext("/", this::handle);
            created.setExecutor(Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "auth-service-stub");
                thread.setDaemon(true);
                return thread;
            }));
            created.start();
            server = created;
        } catch (IOException e) {
            throw new UncheckedIOException("The auth-service stub can't bind its reserved port " + port, e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            received.add(new Received(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Request-Id")));
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            Answer answer = fixedAnswer != null ? fixedAnswer : fixtureAnswer(exchange, path);
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", answer.status() == 200 ? "application/json"
                    : "application/problem+json");
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up (for example after its read timeout); nothing to answer
        }
    }

    private Answer fixtureAnswer(HttpExchange exchange, String path) {
        Matcher access = ACCESS_PATH.matcher(path);
        if (!"GET".equals(exchange.getRequestMethod()) || !access.matches()) {
            return new Answer(404, problem(404, "NOT_FOUND"));
        }
        Optional<UUID> user = subject(exchange.getRequestHeaders().getFirst("Authorization"));
        if (user.isEmpty() || !ReportsFixture.isKnown(user.get()) || rejectedUsers.containsKey(user.get())) {
            return new Answer(401, problem(401, "UNAUTHENTICATED"));
        }
        UUID projectId;
        try {
            projectId = UUID.fromString(access.group(1));
        } catch (IllegalArgumentException e) {
            return new Answer(400, problem(400, "MALFORMED_REQUEST"));
        }
        Optional<String> override = roleOverrides.get(user.get() + "|" + projectId);
        Optional<String> role = override != null ? override : ReportsFixture.role(user.get(), projectId);
        return role.map(r -> new Answer(200, accessBody(projectId, r)))
                .orElseGet(() -> new Answer(403, problem(403, "FORBIDDEN")));
    }

    /** The {@code sub} of a bearer token, read without verification (the stub trusts the service under test). */
    private static Optional<UUID> subject(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String[] parts = authorization.substring(7).split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Matcher subject = SUBJECT.matcher(payload);
        return subject.find() ? Optional.of(UUID.fromString(subject.group(1))) : Optional.empty();
    }

    private static String problem(int status, String code) {
        return "{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code + "\"}";
    }

    /** One request the stub received. */
    public record Received(String method, String path, String authorization, String requestId) {
    }

    private record Answer(int status, String body) {
    }
}
