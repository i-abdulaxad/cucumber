package org.apigateway.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.apigateway.qa.QaSupport.onlyAccessEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * QA adversarial checks around platform-hardening D11 (E13, E14): the abort valve acts only on flagged requests, so
 * large successful responses stay complete, also while other requests on the same gateway are being aborted; other
 * ways of failing after commit (an invalid chunk, a non-streaming body slower than the timeout) abort too.
 */
@DisplayName("QA platform-hardening D11: abort only what failed after commit")
class QaCommittedResponseAbortAdversarialTest extends QaCommittedResponseGatewayTest {

    private static final int BIG = 100_000;

    @Test
    @Tag("AC-6.1")
    @DisplayName("D11 must-not: a complete 100 KB chunked 200 and a complete 200 KB Content-Length 200 arrive whole")
    void completeLargeResponsesAreNotAborted(CapturedOutput output) {
        // Given
        RAW_SCENARIO_SERVICE.on("/api/scenarios/big-ok-chunked", QaCommittedResponseAbortAdversarialTest::completeChunked);
        RAW_SCENARIO_SERVICE.on("/api/scenarios/big-ok-length", out -> {
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 200000\r\nConnection: close\r\n\r\n"));
            out.write(RawStubServer.ascii("k".repeat(200_000)));
        });

        // When
        RawDownload.Received chunked = RawDownload.get(port, "/api/scenarios/big-ok-chunked", "qa-d11-ok-c-" + UUID.randomUUID());
        RawDownload.Received length = RawDownload.get(port, "/api/scenarios/big-ok-length", "qa-d11-ok-l-" + UUID.randomUUID());

        // Then
        assertThat(chunked.end()).isEqualTo(RawDownload.End.COMPLETE);
        assertThat(chunked.body()).isEqualTo("o".repeat(BIG));
        assertThat(length.end()).isEqualTo(RawDownload.End.COMPLETE);
        assertThat(length.body()).hasSize(200_000);
        assertThat(output.getAll()).doesNotContain("Downstream call failed", " ERROR ");
    }

    @Test
    @Tag("AC-6.1")
    @Tag("AC-6.2")
    @DisplayName("D11: an invalid chunk size after 40 KB aborts the connection, logs one WARN and access status=200")
    void anInvalidChunkAfterCommitAborts(CapturedOutput output) {
        // Given 10 valid chunks of 4096 bytes, then an invalid chunk-size line
        RAW_SCENARIO_SERVICE.on("/api/scenarios/big-bad-chunk", out -> {
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
            for (int i = 0; i < 10; i++) {
                out.write(RawStubServer.ascii("1000\r\n" + "b".repeat(4096) + "\r\n"));
            }
            out.write(RawStubServer.ascii("ZZ\r\nnot-a-chunk\r\n0\r\n\r\n"));
        });
        String requestId = "qa-d11-badchunk-" + UUID.randomUUID();

        // When
        RawDownload.Received received = RawDownload.get(port, "/api/scenarios/big-bad-chunk", requestId);

        // Then
        assertAborted(received);
        assertLoggedOnceAsReceived(output, requestId);
    }

    @Test
    @Tag("AC-6.1")
    @Tag("AC-6.2")
    @DisplayName("D11: a JSON (non-streaming) body that stalls after 16 KB for longer than the timeout is aborted too")
    void aNonStreamingBodyStallingAfterCommitIsAborted(CapturedOutput output) {
        // Given
        RAW_SCENARIO_SERVICE.on("/api/scenarios/big-stall-json", out -> {
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
            for (int i = 0; i < 4; i++) {
                out.write(RawStubServer.ascii("1000\r\n" + "s".repeat(4096) + "\r\n"));
            }
            out.flush();
            Thread.sleep(Duration.ofSeconds(6).toMillis());
        });
        String requestId = "qa-d11-stall-" + UUID.randomUUID();

        // When
        RawDownload.Received received = RawDownload.get(port, "/api/scenarios/big-stall-json", requestId);

        // Then
        assertAborted(received);
        assertLoggedOnceAsReceived(output, requestId);
    }

    @Test
    @Tag("AC-6.1")
    @DisplayName("D11 must-not, concurrent: 4 aborted and 4 complete 100 KB downloads at once; only the failed ones abort")
    void abortsDontSpillOverToConcurrentResponses() throws Exception {
        // Given
        RAW_SCENARIO_SERVICE.on("/api/scenarios/mix-ok", QaCommittedResponseAbortAdversarialTest::completeChunked);
        RAW_SCENARIO_SERVICE.on("/api/scenarios/mix-cut", out -> {
            out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
            for (int i = 0; i < 25; i++) {
                out.write(RawStubServer.ascii("1000\r\n" + "x".repeat(4096) + "\r\n"));
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // When
            List<CompletableFuture<RawDownload.Received>> ok = new ArrayList<>();
            List<CompletableFuture<RawDownload.Received>> cut = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ok.add(CompletableFuture.supplyAsync(() -> RawDownload.get(port, "/api/scenarios/mix-ok",
                        "qa-d11-mix-ok-" + UUID.randomUUID()), pool));
                cut.add(CompletableFuture.supplyAsync(() -> RawDownload.get(port, "/api/scenarios/mix-cut",
                        "qa-d11-mix-cut-" + UUID.randomUUID()), pool));
            }

            // Then
            for (CompletableFuture<RawDownload.Received> download : ok) {
                RawDownload.Received received = download.get();
                assertThat(received.end()).as("a complete download").isEqualTo(RawDownload.End.COMPLETE);
                assertThat(received.body()).isEqualTo("o".repeat(BIG));
            }
            for (CompletableFuture<RawDownload.Received> download : cut) {
                assertAborted(download.get());
            }
        } finally {
            pool.shutdownNow();
        }
        // And the gateway still answers the next request normally
        RawDownload.Received after = RawDownload.get(port, "/api/scenarios/after-aborts", "qa-d11-after-" + UUID.randomUUID());
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.end()).isEqualTo(RawDownload.End.COMPLETE);
        assertThat(after.body()).isEqualTo("{}");
    }

    private static void completeChunked(java.io.OutputStream out) throws java.io.IOException {
        out.write(RawStubServer.ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
        for (int i = 0; i < 25; i++) {
            out.write(RawStubServer.ascii("fa0\r\n" + "o".repeat(4000) + "\r\n"));
        }
        out.write(RawStubServer.ascii("0\r\n\r\n"));
    }

    private static void assertAborted(RawDownload.Received received) {
        assertThat(received.status()).isEqualTo(200);
        assertThat(received.endedAbnormally()).as("abnormal end (framing %s, %d bytes, ending ...%s, end %s)",
                received.framing(), received.body().length(), received.tail(), received.end()).isTrue();
        assertThat(received.body()).doesNotContain("Bad Gateway", "\"status\":50", "requestId");
    }

    private static void assertLoggedOnceAsReceived(CapturedOutput output, String requestId) {
        assertThat(onlyAccessEntry(output, requestId)).contains(" status=200 ");
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(output.getAll()).doesNotContain(" ERROR ", "Exception Processing", "\tat ", "Caused by:",
                        "Failure in @ExceptionHandler"));
        assertThat(output.getAll().lines().filter(line -> line.contains("Downstream call failed")).toList())
                .singleElement().satisfies(line -> assertThat(line).contains(" WARN "));
    }
}
