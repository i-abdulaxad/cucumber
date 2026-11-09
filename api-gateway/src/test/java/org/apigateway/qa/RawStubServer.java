package org.apigateway.qa;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QA helper: a downstream stand-in on a plain {@link ServerSocket} that writes scripted raw bytes, so tests can
 * produce what the JDK {@code HttpServer} stub in {@code support/} cannot: invalid status lines, non-HTTP
 * replies, bodies cut off mid-way, or bodies that trickle in slower than the gateway's response timeout.
 */
final class RawStubServer implements AutoCloseable {

    /**
     * Writes the scripted answer for one request; the connection is closed afterwards.
     */
    @FunctionalInterface
    interface Script {
        void answer(OutputStream out) throws IOException, InterruptedException;
    }

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "qa-raw-stub");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Script> scriptsByPath = new ConcurrentHashMap<>();
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final List<String> requestHeads = new CopyOnWriteArrayList<>();

    private RawStubServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    static RawStubServer start() {
        try {
            RawStubServer stub = new RawStubServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            stub.executor.execute(stub::acceptLoop);
            return stub;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String baseUrl() {
        return "http://localhost:" + serverSocket.getLocalPort();
    }

    /**
     * Scripts the answer for requests whose path (without query) equals {@code path}.
     */
    void on(String path, Script script) {
        scriptsByPath.put(path, script);
    }

    List<String> requestLines() {
        return List.copyOf(requestLines);
    }

    /**
     * Values of one request header, in any letter case, for every request received so far, in arrival order.
     */
    List<List<String>> requestHeaderValues(String name) {
        return requestHeads.stream().map(head -> head.lines().skip(1)
                        .filter(line -> line.indexOf(':') > 0
                                && line.substring(0, line.indexOf(':')).trim().equalsIgnoreCase(name))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .toList())
                .toList();
    }

    void reset() {
        requestLines.clear();
        requestHeads.clear();
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing a test stub; nothing to report
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> serve(socket));
            } catch (SocketException closed) {
                return;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            String head = readHead(socket.getInputStream());
            String requestLine = head.lines().findFirst().orElse("");
            requestLines.add(requestLine);
            requestHeads.add(head);
            String target = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Script script = scriptsByPath.getOrDefault(path,
                    out -> out.write(ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")));
            OutputStream out = socket.getOutputStream();
            script.answer(out);
            out.flush();
        } catch (IOException ignored) {
            // the gateway may close the connection first (for example on its response timeout)
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int next;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched < terminator.length && (next = in.read()) != -1) {
            head.write(next);
            matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }
}
