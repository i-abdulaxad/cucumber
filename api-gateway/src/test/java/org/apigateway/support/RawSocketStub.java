package org.apigateway.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for a downstream service that writes scripted raw bytes on a plain socket (D8). It produces replies
 * that the JDK {@code HttpServer} behind {@link StubServer} cannot: a status line with any code and reason phrase,
 * a body cut off before its declared {@code Content-Length}, or a body sent in parts with pauses in between.
 * The connection is closed after each reply.
 */
public final class RawSocketStub implements AutoCloseable {

    /**
     * Writes the reply to one request.
     */
    @FunctionalInterface
    public interface Reply {
        void writeTo(OutputStream out) throws IOException, InterruptedException;
    }

    private static final byte[] END_OF_HEAD = {'\r', '\n', '\r', '\n'};

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private volatile Reply reply = out -> out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"));

    private RawSocketStub(ServerSocket serverSocket, ExecutorService executor) {
        this.serverSocket = serverSocket;
        this.executor = executor;
    }

    public static RawSocketStub start(String name) {
        try {
            ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            AtomicInteger counter = new AtomicInteger();
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "raw-stub-" + name + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            RawSocketStub stub = new RawSocketStub(serverSocket, executor);
            executor.execute(stub::acceptConnections);
            return stub;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not start raw stub " + name, ex);
        }
    }

    public static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    public String baseUrl() {
        return "http://localhost:" + serverSocket.getLocalPort();
    }

    public void respondWith(Reply scripted) {
        this.reply = scripted;
    }

    /**
     * Request lines (for example {@code GET /api/scenarios/42 HTTP/1.1}) in the order they arrived.
     */
    public List<String> requestLines() {
        return List.copyOf(requestLines);
    }

    public void reset() {
        requestLines.clear();
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            executor.shutdownNow();
        }
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                Socket connection = serverSocket.accept();
                executor.execute(() -> answer(connection));
            } catch (SocketException closed) {
                return;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    private void answer(Socket connection) {
        try (connection) {
            requestLines.add(readHead(connection.getInputStream()).lines().findFirst().orElse(""));
            OutputStream out = connection.getOutputStream();
            reply.writeTo(out);
            out.flush();
        } catch (IOException gatewayClosedTheConnection) {
            // Expected when the gateway gives up first, for example on its response timeout.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads the request line and headers; the stubbed requests carry no body.
     */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int next;
        while (matched < END_OF_HEAD.length && (next = in.read()) != -1) {
            head.write(next);
            matched = next == END_OF_HEAD[matched] ? matched + 1 : (next == END_OF_HEAD[0] ? 1 : 0);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }
}
