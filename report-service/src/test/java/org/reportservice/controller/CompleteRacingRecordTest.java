package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.HAL;

/**
 * AC-4.8 (D14, BD-14; a concurrency class, run 3 times at review points): Hal completes a fresh run while Cai records a
 * result on it, 20 times. The project lock serialises them and the recording re-reads the run under it: each
 * recording is 200 with {@code finishedAt} not after the run's, or 409 RUN_CLOSED; never a 500.
 */
@ReportServiceIntegrationTest
class CompleteRacingRecordTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @RepeatedTest(20)
    @Tag("AC-4.8")
    void completeRacingARecording() throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "IN_PROGRESS", 1);
        CyclicBarrier start = new CyclicBarrier(2);

        CompletableFuture<MvcResult> complete = CompletableFuture.supplyAsync(() -> call(start,
                () -> Runs.complete(mockMvc, HAL, CHECKOUT, run.id())));
        CompletableFuture<MvcResult> record = CompletableFuture.supplyAsync(() -> call(start,
                () -> Runs.record(mockMvc, CAI, CHECKOUT, run.id(), run.results().get(0), "{\"status\":\"PASSED\"}")));
        MvcResult completed = complete.get(30, TimeUnit.SECONDS);
        MvcResult recorded = record.get(30, TimeUnit.SECONDS);

        assertThat(completed.getResponse().getStatus()).as(body(completed)).isEqualTo(200);
        Instant runFinishedAt = Instant.parse(JsonPath.read(body(completed), "$.finishedAt"));
        if (recorded.getResponse().getStatus() == 200) {
            assertThat(Instant.parse(JsonPath.read(body(recorded), "$.finishedAt")))
                    .isBeforeOrEqualTo(runFinishedAt);
        } else {
            assertProblem(recorded, 409, "RUN_CLOSED");
            assertThat(jdbc.queryForObject("SELECT status FROM test_case_results", String.class))
                    .isEqualTo("UNKNOWN");
        }
    }

    private static MvcResult call(CyclicBarrier start, Call call) {
        try {
            start.await(10, TimeUnit.SECONDS);
            return call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface Call {
        MvcResult run() throws Exception;
    }
}
