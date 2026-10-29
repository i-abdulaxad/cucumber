package org.reportservice.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.ReportsFixture.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-1.9 (D4, D14; a concurrency class, run 3 times at review points): with 9 active tokens, Cai and Ana create one
 * each at the same moment. The project lock serialises them: exactly one 201 and one 409 UPLOAD_TOKEN_LIMIT, never a
 * 500, and 10 active tokens afterwards. Repeated 10 times per run.
 */
@ReportServiceIntegrationTest
class ConcurrentTokenCreateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void nineActiveTokens() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        for (int i = 0; i < 9; i++) {
            ReportData.token(jdbc, CHECKOUT, "A" + i, ReportData.randomToken(), CAI.id(), Instant.now(), null, null);
        }
    }

    @RepeatedTest(10)
    @Tag("AC-1.9")
    void twoConcurrentCreatesAtTheLimit() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        CompletableFuture<MvcResult> cai = CompletableFuture.supplyAsync(() -> create(CAI, start));
        CompletableFuture<MvcResult> ana = CompletableFuture.supplyAsync(() -> create(ANA, start));

        List<MvcResult> results = List.of(cai.get(20, TimeUnit.SECONDS), ana.get(20, TimeUnit.SECONDS));

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        assertProblem(results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst()
                .orElseThrow(), 409, "UPLOAD_TOKEN_LIMIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens WHERE project_id = ?", Integer.class,
                CHECKOUT)).isEqualTo(10);
    }

    private MvcResult create(User caller, CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/projects/" + CHECKOUT + "/upload-tokens")
                    .header(HttpHeaders.AUTHORIZATION, caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Race\"}")).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
