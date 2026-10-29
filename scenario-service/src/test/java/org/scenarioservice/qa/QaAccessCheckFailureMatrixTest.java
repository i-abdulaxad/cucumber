package org.scenarioservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.scenarioservice.support.TestSettings;
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
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.list;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.ACME;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * QA (adversarial, D4 and AC-7.3 / AC-7.4): the access check C5 failure matrix beyond the spec's seven rows. Every
 * answer that isn't exactly "200 with the requested projectId, a known role and a boolean archived", 401 or 403 must
 * fail closed with 503 ACCESS_CHECK_UNAVAILABLE within about 3 s (1 s connect, 2 s read, no retry), and nothing may be
 * stored. Transport oddities (a reset connection, a garbage answer, a body that stalls after the headers) are played by
 * a raw socket server that takes over the stub's reserved port for one test; the stub is restarted afterwards.
 */
@ScenarioServiceIntegrationTest
class QaAccessCheckFailureMatrixTest {

    private static final Duration FAIL_CLOSED_WITHIN = Duration.ofSeconds(3);

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
    }

    @AfterEach
    void restartTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "C5 answers {0}: 503, nothing stored")
    @ValueSource(strings = {"role in lower case", "role with a trailing space", "role as a number", "role null",
            "archived null", "archived as a number", "projectId missing", "projectId not canonical",
            "projectId as urn", "a JSON string", "a JSON number", "status 400", "status 409", "status 429",
            "status 301", "status 307", "status 502", "status 503", "status 504", "a body over 16 KiB"})
    @Tag("AC-7.3")
    void oddAnswersFailClosed(String answer) throws Exception {
        String valid = AccessCheckStub.accessBody(CHECKOUT, "MEMBER");
        switch (answer) {
            case "role in lower case" -> stub.answer(200, valid.replace("\"MEMBER\"", "\"member\""));
            case "role with a trailing space" -> stub.answer(200, valid.replace("\"MEMBER\"", "\"MEMBER \""));
            case "role as a number" -> stub.answer(200, valid.replace("\"MEMBER\"", "3"));
            case "role null" -> stub.answer(200, valid.replace("\"MEMBER\"", "null"));
            case "archived null" -> stub.answer(200, valid.replace("\"archived\":false", "\"archived\":null"));
            case "archived as a number" -> stub.answer(200, valid.replace("\"archived\":false", "\"archived\":0"));
            case "projectId missing" -> stub.answer(200, valid.replace("\"projectId\":\"" + CHECKOUT + "\",", ""));
            case "projectId not canonical" -> stub.answer(200, valid.replace(CHECKOUT.toString(),
                    CHECKOUT.toString().replace("-", "")));
            case "projectId as urn" -> stub.answer(200, valid.replace(CHECKOUT.toString(), "urn:uuid:" + CHECKOUT));
            case "a JSON string" -> stub.answer(200, "\"MEMBER\"");
            case "a JSON number" -> stub.answer(200, "3");
            case "status 400" -> stub.answer(400, "{\"code\":\"MALFORMED_REQUEST\"}");
            case "status 409" -> stub.answer(409, valid);
            case "status 429" -> stub.answer(429, "");
            case "status 301" -> stub.answer(301, "");
            case "status 307" -> stub.answer(307, valid);
            case "status 502" -> stub.answer(502, "");
            case "status 503" -> stub.answer(503, "");
            case "status 504" -> stub.answer(504, "");
            default -> stub.answer(200, valid.replace("{", "{" + " ".repeat(17 * 1024)));
        }

        assertFailsClosed();
        assertThat(stub.received()).as("exactly one call, no retry and no redirect followed").hasSize(1);
    }

    @ParameterizedTest(name = "C5 answers 200 with {0}: the request proceeds")
    @ValueSource(strings = {"the projectId in upper case", "extra members", "members in another order"})
    @Tag("AC-7.3")
    void harmlessVariationsOfAValidAnswerProceed(String answer) throws Exception {
        String valid = AccessCheckStub.accessBody(CHECKOUT, "MEMBER");
        switch (answer) {
            case "the projectId in upper case" -> stub.answer(200, valid.replace(CHECKOUT.toString(),
                    CHECKOUT.toString().toUpperCase()));
            case "extra members" -> stub.answer(200, valid.replace("{", "{\"since\":\"2026-10-01T00:00:00Z\","));
            default -> stub.answer(200, "{\"archived\":false,\"role\":\"MEMBER\",\"orgId\":\"" + ACME
                    + "\",\"projectId\":\"" + CHECKOUT + "\"}");
        }

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-7.4")
    @DisplayName("C5 answers 401 without a body: 401 UNAUTHENTICATED with the Bearer challenge, nothing stored")
    void a401WithoutBodyIsUnauthenticated() throws Exception {
        stub.answer(401, "");

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @Test
    @DisplayName("C5 answers 403 with a body that isn't JSON: still 403 FORBIDDEN (the status decides)")
    void a403WithAnyBodyIsForbidden() throws Exception {
        stub.answer(403, "<html>denied</html>");

        assertProblem(create(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS), 403,
                "FORBIDDEN");
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("A read fails closed too: the list is a 503 Problem, never data")
    void aReadFailsClosed() throws Exception {
        create(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);
        stub.answer(500, "{}");

        MvcResult result = list(mockMvc, GUS, CHECKOUT);

        assertProblem(result, 503, "ACCESS_CHECK_UNAVAILABLE");
        assertThat(body(result)).doesNotContain("features/address.feature", "Address");
    }

    @ParameterizedTest(name = "auth-service {0}: 503 within 3 s, nothing stored")
    @ValueSource(strings = {"closes the connection at once", "answers garbage", "stalls after sending part of the head",
            "stalls in the body after a complete head", "accepts but never answers", "resets before reading the request"})
    @Tag("AC-7.3")
    @Tag("AC-1.1")
    void transportFailuresFailClosed(String behaviour) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (RawServer server = RawServer.takeOverStubPort(stub, socket -> {
            connections.incrementAndGet();
            if (behaviour.equals("resets before reading the request")) {
                socket.setSoLinger(true, 0);
                socket.close();
                return;
            }
            readRequestHead(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            switch (behaviour) {
                case "closes the connection at once" -> socket.close();
                case "answers garbage" -> {
                    out.write("HELLO THERE\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    socket.close();
                }
                case "stalls after sending part of the head" -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    sleep(Duration.ofSeconds(6));
                }
                case "stalls in the body after a complete head" -> {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 400\r\n\r\n"
                            + "{\"projectId\":\"" + CHECKOUT + "\",").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    sleep(Duration.ofSeconds(6));
                }
                default -> sleep(Duration.ofSeconds(6));
            }
        })) {
            assertFailsClosed();
        }
        // Every row, including "closes the connection at once": restored by qa at platform-hardening review point B
        // (2026-10-29). That row was exempt while the JDK HTTP client retried it (scenario-service BUG-4, SFU-1); T3 limits
        // every access check to one attempt (D2, AC-1.1).
        assertThat(connections.get()).as("no retry").isEqualTo(1);
    }

    private void assertFailsClosed() throws Exception {
        long start = System.nanoTime();

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);

        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertProblem(result, 503, "ACCESS_CHECK_UNAVAILABLE");
        assertThat(took).isLessThan(FAIL_CLOSED_WITHIN);
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
        assertThat(JsonPath.<Object>read(body(result), "$")).asString().doesNotContain(
                String.valueOf(TestSettings.ACCESS_CHECK_PORT));
    }

    static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int read;
        while ((read = in.read()) != -1) {
            matched = read == end[matched] ? matched + 1 : (read == '\r' ? 1 : 0);
            if (matched == end.length) {
                return;
            }
        }
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One connection handler of the raw server. */
    @FunctionalInterface
    interface Handler {
        void handle(Socket socket) throws IOException;
    }

    /**
     * A raw TCP server on the access check stub's reserved port, for answers the JDK HTTP server can't give. It stops
     * the stub, binds the port, serves each connection on its own daemon thread, and on close releases the port; the
     * test's {@code stub.reset()} then binds the stub again.
     */
    static final class RawServer implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread acceptor;

        private RawServer(ServerSocket socket, Handler handler) {
            this.socket = socket;
            this.acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket connection = socket.accept();
                        Thread worker = new Thread(() -> {
                            try (connection) {
                                handler.handle(connection);
                            } catch (IOException ignored) {
                                // the client gave up; nothing to do
                            }
                        }, "qa-raw-c5-connection");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "qa-raw-c5");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        static RawServer takeOverStubPort(AccessCheckStub stub, Handler handler) throws IOException {
            stub.stop();
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), TestSettings.ACCESS_CHECK_PORT), 50);
            return new RawServer(socket, handler);
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
