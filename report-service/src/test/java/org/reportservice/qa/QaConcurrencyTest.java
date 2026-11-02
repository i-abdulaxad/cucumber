package org.reportservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.ReportsFixture.User;
import org.reportservice.support.Uploads;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, concurrency (D14; AC-1.9, AC-2.11, AC-2.14 extended): races at the token limit over many rounds, find-or-create
 * of environments and builds, revoke racing create, the 1 s test {@code lock_timeout} answering 409 CONFLICT with
 * nothing stored, and project locks that never block another project. Run 3 times in a row (standards §5).
 */
@ReportServiceIntegrationTest
class QaConcurrencyTest {

    private static final String TOKENS = "/api/projects/" + CHECKOUT + "/upload-tokens";

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-1.9")
    @DisplayName("AC-1.9 qa: five concurrent creates at 9 active give exactly one 201, ten rounds")
    void fiveCreatesAtTheLimit() throws Exception {
        for (int round = 0; round < 10; round++) {
            ReportData.reset(jdbc);
            for (int i = 0; i < 9; i++) {
                ReportData.token(jdbc, CHECKOUT, "t" + i, ReportData.randomToken(), CAI.id(), Instant.now(), null,
                        null);
            }
            CyclicBarrier start = new CyclicBarrier(5);
            List<CompletableFuture<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                User caller = i % 2 == 0 ? CAI : ANA;
                futures.add(async(start, () -> create(caller, "{\"name\":\"race\"}")));
            }
            List<Integer> statuses = statuses(futures);
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409, 409, 409, 409);
            assertThat(activeTokens()).isEqualTo(10);
        }
    }

    @Test
    @Tag("AC-1.9")
    @DisplayName("D14 qa: revokes racing creates at 10 active never exceed 10 active and never 500")
    void revokeRacingCreate() throws Exception {
        for (int round = 0; round < 10; round++) {
            ReportData.reset(jdbc);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add(ReportData.token(jdbc, CHECKOUT, "t" + i, ReportData.randomToken(), CAI.id(), Instant.now(),
                        null, null));
            }
            CyclicBarrier start = new CyclicBarrier(4);
            List<CompletableFuture<MvcResult>> futures = List.of(
                    async(start, () -> create(CAI, "{\"name\":\"a\"}")),
                    async(start, () -> create(ANA, "{\"name\":\"b\"}")),
                    async(start, () -> revoke(CAI, ids.get(0))),
                    async(start, () -> revoke(ANA, ids.get(1))));
            List<Integer> statuses = statuses(futures);
            assertThat(statuses.subList(2, 4)).containsOnly(200);
            assertThat(statuses.subList(0, 2)).allMatch(status -> status == 201 || status == 409);
            assertThat(activeTokens()).isLessThanOrEqualTo(10);
        }
    }

    @Test
    @Tag("AC-2.11")
    @DisplayName("AC-2.11 qa: two uploads naming the same new environment and build create one row each, ten rounds")
    void concurrentFindOrCreate() throws Exception {
        String token = Uploads.activeToken(jdbc, CHECKOUT);
        for (int round = 0; round < 10; round++) {
            String query = "environment=env-" + round + "&build=b-" + round;
            CyclicBarrier start = new CyclicBarrier(2);
            List<CompletableFuture<MvcResult>> futures = List.of(
                    async(start, () -> upload(token, query + "&branch=first")),
                    async(start, () -> upload(token, query + "&branch=second")));
            assertThat(statuses(futures)).containsExactly(201, 201);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM environments WHERE name = ?", Integer.class,
                    "env-" + round)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM builds WHERE name = ?", Integer.class,
                    "b-" + round)).isEqualTo(1);
            List<String> buildIds = new ArrayList<>();
            for (CompletableFuture<MvcResult> future : futures) {
                buildIds.add(JsonPath.read(body(future.get()), "$.build.id"));
            }
            assertThat(buildIds.get(0)).isEqualTo(buildIds.get(1));
        }
    }

    @Test
    @Tag("AC-2.14")
    @DisplayName("D14 qa: with the project lock held elsewhere, an upload and a token create are 409 CONFLICT after "
            + "about 1 s, nothing stored, lastUsedAt untouched; Billing is not blocked")
    void lockTimeoutAndIsolation() throws Exception {
        String t1 = Uploads.activeToken(jdbc, CHECKOUT);
        String b1 = Uploads.activeToken(jdbc, BILLING);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> holdLock(CHECKOUT, locked, release));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            MvcResult upload = upload(t1, "environment=locked-env");
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            assertProblem(upload, 409, "CONFLICT");
            assertThat(waited).isBetween(Duration.ofMillis(800), Duration.ofSeconds(4));
            assertProblem(create(CAI, "{\"name\":\"locked\"}"), 409, "CONFLICT");

            long billingStart = System.nanoTime();
            MvcResult billing = upload(b1, "");
            assertThat(billing.getResponse().getStatus()).as(body(billing)).isEqualTo(201);
            assertThat(Duration.ofNanos(System.nanoTime() - billingStart)).isLessThan(Duration.ofMillis(900));
        } finally {
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE project_id = ?", Integer.class, CHECKOUT))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens WHERE project_id = ?", Integer.class,
                CHECKOUT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM upload_tokens WHERE project_id = ?", Instant.class,
                CHECKOUT)).isNull();
    }

    @Test
    @Tag("AC-4.8")
    @DisplayName("AC-4.8 qa: complete racing cancel on a planned run: one wins, the other is 409 RUN_CLOSED, 20 rounds")
    void completeRacingCancel() throws Exception {
        for (int round = 0; round < 20; round++) {
            ReportData.reset(jdbc);
            RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
            CyclicBarrier start = new CyclicBarrier(2);
            List<CompletableFuture<MvcResult>> futures = List.of(
                    async(start, () -> call(() -> Runs.complete(mockMvc, HAL, CHECKOUT, run.id()))),
                    async(start, () -> call(() -> Runs.cancel(mockMvc, GUS, CHECKOUT, run.id()))));
            List<Integer> statuses = statuses(futures);
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            String winner = statuses.get(0) == 200 ? "COMPLETED" : "CANCELLED";
            assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo(winner);
            MvcResult loser = futures.get(statuses.get(0) == 200 ? 1 : 0).get();
            assertProblem(loser, 409, "RUN_CLOSED");
        }
    }

    @Test
    @Tag("AC-4.8")
    @DisplayName("D8 qa: two recordings on one result end as exactly one of them; two first recordings set startedAt once")
    void concurrentRecordings() throws Exception {
        for (int round = 0; round < 20; round++) {
            ReportData.reset(jdbc);
            RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 2);
            UUID shared = run.results().get(0);
            CyclicBarrier start = new CyclicBarrier(3);
            List<CompletableFuture<MvcResult>> futures = List.of(
                    async(start, () -> call(() -> Runs.record(mockMvc, HAL, CHECKOUT, run.id(), shared,
                            "{\"status\":\"PASSED\",\"comment\":\"hal\"}"))),
                    async(start, () -> call(() -> Runs.record(mockMvc, CAI, CHECKOUT, run.id(), shared,
                            "{\"status\":\"FAILED\",\"comment\":\"cai\"}"))),
                    async(start, () -> call(() -> Runs.record(mockMvc, HAL, CHECKOUT, run.id(), run.results().get(1),
                            "{\"status\":\"SKIPPED\"}"))));
            assertThat(statuses(futures)).containsOnly(200);
            java.util.Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, comment, executed_by FROM test_case_results WHERE id = ?", shared);
            String combined = row.get("status") + "|" + row.get("comment") + "|" + row.get("executed_by");
            assertThat(combined).isIn("PASSED|hal|" + HAL.id(), "FAILED|cai|" + CAI.id());
            assertThat(jdbc.queryForObject("SELECT status FROM test_runs", String.class)).isEqualTo("IN_PROGRESS");
            Instant startedAt = jdbc.queryForObject("SELECT started_at FROM test_runs", Instant.class);
            Instant firstFinished = null;
            for (CompletableFuture<MvcResult> future : futures) {
                Instant finished = Instant.parse(JsonPath.read(body(future.get()), "$.finishedAt"));
                firstFinished = firstFinished == null || finished.isBefore(firstFinished) ? finished : firstFinished;
            }
            assertThat(startedAt).as("startedAt is the first recording's time, set once").isEqualTo(firstFinished);
        }
    }

    private interface Call {
        MvcResult run() throws Exception;
    }

    private static MvcResult call(Call call) {
        try {
            return call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void holdLock(UUID projectId, CountDownLatch locked, CountDownLatch release) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, projectId.getMostSignificantBits() ^ projectId.getLeastSignificantBits());
                lock.execute();
            }
            locked.countDown();
            release.await(15, TimeUnit.SECONDS);
            connection.rollback();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletableFuture<MvcResult> async(CyclicBarrier start, Supplier<MvcResult> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return call.get();
        });
    }

    private static List<Integer> statuses(List<CompletableFuture<MvcResult>> futures) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).as(body(result)).isNotEqualTo(500);
            statuses.add(result.getResponse().getStatus());
        }
        return statuses;
    }

    private MvcResult create(User caller, String json) {
        try {
            return mockMvc.perform(post(TOKENS).header(HttpHeaders.AUTHORIZATION, caller.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult revoke(User caller, UUID id) {
        try {
            return mockMvc.perform(post(TOKENS + "/" + id + "/revoke")
                    .header(HttpHeaders.AUTHORIZATION, caller.bearer())).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult upload(String token, String query) {
        try {
            return Uploads.upload(mockMvc, token, query, CiSample.JSON);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int activeTokens() {
        return jdbc.queryForObject("SELECT count(*) FROM upload_tokens WHERE project_id = ? AND revoked_at IS NULL",
                Integer.class, CHECKOUT);
    }
}
