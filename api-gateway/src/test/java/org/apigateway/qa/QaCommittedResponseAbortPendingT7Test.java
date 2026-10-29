package org.apigateway.qa;

import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.apigateway.qa.QaSupport.onlyAccessEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * platform-hardening US-6, AC-6.1 and AC-6.2 (D11, gateway FU-4 abort, BUG-api-gateway-14's reproductions): once the
 * response is committed, a downstream failure or response timeout must abort the client connection, write nothing
 * more, log one WARN and record the status the client actually received.
 *
 * <p><b>PENDING T7: this class is {@code abstract} on purpose.</b> Its tests fail before T7 (qa showed them red), and
 * a red test can't be committed or disabled (gateway D10, platform-hardening T7 step 0). An abstract class is never
 * discovered, so the full build neither runs nor skips it. T7 removes the {@code abstract} modifier (the only edit to
 * this file, by F1 exception) and makes the tests green in the same change set.
 */
@DisplayName("QA platform-hardening US-6: a downstream failure after commit aborts the client connection (pending T7)")
abstract class QaCommittedResponseAbortPendingT7Test extends QaCommittedResponseGatewayTest {

    private static final String DOWNSTREAM_FAILED = "Downstream call failed";
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 10_000;

    /** The AC-6.1 Examples rows; the stub always adds {@code Connection: close} so no closed connection is reused. */
    enum Row {
        BIG_CUT_CHUNKED("big-cut-chunked", out -> {
            // 25 chunks of 4096 bytes, then closes without the terminating chunk
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
            String chunk = "1000\r\n" + "c".repeat(4096) + "\r\n";
            for (int i = 0; i < 25; i++) {
                out.write(RawStubServer.ascii(chunk));
            }
        }),
        BIG_CUT_LENGTH("big-cut-length", out -> {
            // Content-Length 200000, 100,000 bytes, then closes
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 200000\r\nConnection: close\r\n\r\n"));
            out.write(RawStubServer.ascii("l".repeat(100_000)));
        }),
        SLOW_STREAM("slow-stream", out -> {
            // 20 bytes, then nothing for longer than the 2 s response timeout
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
            out.write(RawStubServer.ascii("14\r\ndata: qa-event-0001\n\r\n"));
            out.flush();
            Thread.sleep(Duration.ofSeconds(6).toMillis());
        });

        final String path;
        final RawStubServer.Script script;

        Row(String name, RawStubServer.Script script) {
            this.path = "/api/scenarios/" + name;
            this.script = script;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Row.class)
    @Tag("AC-6.1")
    @DisplayName("AC-6.1: a downstream failure after commit gives status 200, then an abnormal end, and no appended Problem")
    void aFailureAfterCommitAbortsTheClientConnection(Row row) {
        // Given
        RAW_SCENARIO_SERVICE.on(row.path, row.script);

        // When ana sends GET <path> with {valid token}
        Received received = download(row.path, newRequestId(row));

        // Then the client receives status 200 and then an abnormal end of the connection
        assertThat(received.status()).as("status").isEqualTo(200);
        assertThat(received.end()).as("how the response ended (framing %s, %d body bytes, ending ...%s)",
                        received.framing(), received.body().length(), received.tail())
                .isIn(End.EOF_BEFORE_COMPLETE, End.CONNECTION_RESET);
        // And the received body does not contain "Bad Gateway", "\"status\":502" or "\"status\":500"
        assertThat(received.body()).doesNotContain("Bad Gateway", "\"status\":502", "\"status\":500");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Row.class)
    @Tag("AC-6.2")
    @DisplayName("AC-6.2: one WARN without stack trace, no ERROR or error dispatch, access entry status=200")
    void aFailureAfterCommitIsLoggedOnceAtWarnWithTheStatusTheClientGot(Row row, CapturedOutput output) {
        // When the <path> row of AC-6.1 runs
        RAW_SCENARIO_SERVICE.on(row.path, row.script);
        String requestId = newRequestId(row);
        download(row.path, requestId);

        // Then the access entry for that requestId says status=200
        assertThat(onlyAccessEntry(output, requestId)).contains(" status=200 ");
        // And no ERROR entry and no "Exception Processing" entry is logged (waiting briefly for late container logs);
        // D11.2 "logged once at WARN" also rules out Spring's second WARN for a failing exception handler
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(output.getAll()).doesNotContain(" ERROR ", "Exception Processing", "\tat ", "Caused by:",
                        "Failure in @ExceptionHandler"));
        // And exactly one WARN "Downstream call failed" line is logged, without a stack trace
        List<String> failureLines = output.getAll().lines().filter(line -> line.contains(DOWNSTREAM_FAILED)).toList();
        assertThat(failureLines).singleElement().satisfies(line -> assertThat(line).contains(" WARN "));
    }

    // ---- raw client: tells a cleanly framed end from an abnormal one ------------------------------------------------

    enum End { COMPLETE, EOF_BEFORE_COMPLETE, CONNECTION_RESET, CLOSE_DELIMITED, CLIENT_TIMEOUT }

    record Received(int status, String framing, String body, End end) {

        String tail() {
            return body.substring(Math.max(0, body.length() - 300));
        }
    }

    private static String newRequestId(Row row) {
        return "qa-fu4-" + row.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
    }

    /**
     * GET over a plain socket with keep-alive (no {@code Connection: close}), so a cleanly framed response is
     * recognised by its framing alone: the terminating chunk, or {@code Content-Length} bytes.
     */
    private Received download(String path, String requestId) {
        String request = "GET " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Authorization: " + TestTokens.bearerValid() + "\r\nX-Request-Id: " + requestId + "\r\n\r\n";
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String head = readLine(in);
            assertThat(head).as("status line").isNotNull();
            int status = Integer.parseInt(head.split(" ")[1]);
            String framing = "close-delimited";
            long contentLength = -1;
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    framing = "chunked";
                } else if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    framing = "content-length " + contentLength;
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            End end;
            try {
                if (framing.equals("chunked")) {
                    end = readChunked(in, body);
                } else if (contentLength >= 0) {
                    end = copy(in, body, contentLength) ? End.COMPLETE : End.EOF_BEFORE_COMPLETE;
                } else {
                    copy(in, body, Long.MAX_VALUE);
                    end = End.CLOSE_DELIMITED;
                }
            } catch (SocketTimeoutException hung) {
                end = End.CLIENT_TIMEOUT;
            } catch (IOException reset) {
                end = End.CONNECTION_RESET;
            }
            return new Received(status, framing, body.toString(StandardCharsets.UTF_8), end);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static End readChunked(InputStream in, ByteArrayOutputStream body) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return End.EOF_BEFORE_COMPLETE;
            }
            int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
            if (size == 0) {
                for (String trailer = readLine(in); ; trailer = readLine(in)) {
                    if (trailer == null) {
                        return End.EOF_BEFORE_COMPLETE;
                    }
                    if (trailer.isEmpty()) {
                        return End.COMPLETE;
                    }
                }
            }
            if (!copy(in, body, size) || readLine(in) == null) {
                return End.EOF_BEFORE_COMPLETE;
            }
        }
    }

    /** Copies up to {@code length} bytes; false if the stream ended first. */
    private static boolean copy(InputStream in, ByteArrayOutputStream body, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                return false;
            }
            body.write(buffer, 0, read);
            remaining -= read;
        }
        return true;
    }

    /** One CRLF-terminated line without the CRLF, or null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int next;
        while ((next = in.read()) != -1) {
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(next);
            previous = next;
        }
        return null;
    }
}
