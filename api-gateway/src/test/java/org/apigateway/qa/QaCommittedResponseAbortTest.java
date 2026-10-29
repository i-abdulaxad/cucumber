package org.apigateway.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;

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
 * <p>Written before T7 and shown red then (2026-10-26: the chunked row completed cleanly with the 502 Problem appended;
 * the length row got the Problem appended inside its {@code Content-Length} body and the connection was held open; the
 * stream row was logged as status=500 with three ERROR entries). Green since T7 (E13 handler flag, E14 abort valve).
 * Formerly {@code QaCommittedResponseAbortPendingT7Test}, kept out of the build as an abstract class until T7 landed.
 */
@DisplayName("QA platform-hardening US-6: a downstream failure after commit aborts the client connection")
class QaCommittedResponseAbortTest extends QaCommittedResponseGatewayTest {

    private static final String DOWNSTREAM_FAILED = "Downstream call failed";

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
        RawDownload.Received received = RawDownload.get(port, row.path, newRequestId(row));

        // Then the client receives status 200 and then an abnormal end of the connection
        assertThat(received.status()).as("status").isEqualTo(200);
        assertThat(received.end()).as("how the response ended (framing %s, %d body bytes, ending ...%s)",
                        received.framing(), received.body().length(), received.tail())
                .isIn(RawDownload.End.EOF_BEFORE_COMPLETE, RawDownload.End.CONNECTION_RESET);
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
        RawDownload.get(port, row.path, requestId);

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

    private static String newRequestId(Row row) {
        return "qa-fu4-" + row.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
    }
}
