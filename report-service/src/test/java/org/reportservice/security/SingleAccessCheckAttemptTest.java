package org.reportservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.TestSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * RE6, RE7 (as platform-hardening US-1): every access check is one C5 request on one connection, whatever the
 * transport failure, and still fails closed with 503 within the 1 s connect and 2 s read timeouts (AC-6.3). Transport
 * failures are played by a raw socket server on the auth-service stub's reserved port; the stub is restarted
 * afterwards.
 */
@ReportServiceIntegrationTest
class SingleAccessCheckAttemptTest {

    /** The AC-6.3 bound: 1 s connect and 2 s read timeouts, no retry. */
    private static final Duration FAIL_CLOSED_WITHIN = Duration.ofSeconds(3);

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void restartTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "AC-6.3 auth-service {0}: one connection, at most one request, 503")
    @ValueSource(strings = {"closes the connection at once", "answers garbage",
            "stalls after sending part of the head", "resets the connection after reading the request"})
    @Tag("AC-6.3")
    void aTransportFailureIsOneAttempt(String behaviour) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        MvcResult result;
        Duration took;
        try (RawServer ignored = RawServer.onTheStubPort(stub, connections, socket -> {
            if (readRequestHead(socket.getInputStream())) {
                requests.incrementAndGet();
            }
            OutputStream out = socket.getOutputStream();
            switch (behaviour) {
                case "closes the connection at once" -> socket.close();
                case "answers garbage" -> {
                    out.write("HELLO THERE\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    socket.close();
                }
                case "stalls after sending part of the head" -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    sleep(Duration.ofSeconds(5));
                }
                default -> {
                    socket.setSoLinger(true, 0);
                    socket.close();
                }
            }
        })) {
            long start = System.nanoTime();
            result = probe();
            took = Duration.ofNanos(System.nanoTime() - start);
        }

        assertProblem(result, 503, "ACCESS_CHECK_UNAVAILABLE");
        assertThat(took).isLessThan(FAIL_CLOSED_WITHIN);
        assertThat(connections.get()).as("connections accepted").isEqualTo(1);
        assertThat(requests.get()).as("requests received").isLessThanOrEqualTo(1);
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3 a normal answer is still exactly one C5 request")
    void aNormalAnswerIsOneRequest() throws Exception {
        stub.answer(200, AuthServiceStub.accessBody(CHECKOUT, "READER"));

        MvcResult result = probe();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stub.received()).hasSize(1);
    }

    private MvcResult probe() throws Exception {
        return mockMvc.perform(get("/api/projects/" + CHECKOUT + "/probe")
                .header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();
    }

    /** Reads up to the end of the request head; whether a complete head arrived. */
    private static boolean readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int read;
        while ((read = in.read()) != -1) {
            matched = read == end[matched] ? matched + 1 : (read == '\r' ? 1 : 0);
            if (matched == end.length) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One connection handler of the raw server. */
    @FunctionalInterface
    private interface Handler {
        void handle(Socket socket) throws IOException;
    }

    /**
     * A raw TCP server on the access check stub's reserved port: it stops the stub, counts every accepted connection,
     * serves each on its own daemon thread, and releases the port on close.
     */
    private static final class RawServer implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread acceptor;

        private RawServer(ServerSocket socket, AtomicInteger connections, Handler handler) {
            this.socket = socket;
            this.acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket connection = socket.accept();
                        connections.incrementAndGet();
                        Thread worker = new Thread(() -> {
                            try (connection) {
                                handler.handle(connection);
                            } catch (IOException ignored) {
                                // the client gave up; nothing to do
                            }
                        }, "raw-c5-connection");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "raw-c5");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        static RawServer onTheStubPort(AuthServiceStub stub, AtomicInteger connections, Handler handler)
                throws IOException {
            stub.stop();
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), TestSettings.AUTH_SERVICE_PORT), 50);
            return new RawServer(socket, connections, handler);
        }

        @Override
        public void close() throws IOException {
            socket.close();
            try {
                acceptor.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
