package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reportservice.dto.request.CiUploadQuery;
import org.reportservice.dto.request.UploadBody;
import org.reportservice.exception.UploadTokenInvalidException;
import org.reportservice.security.UploadTokenPrincipal;
import org.reportservice.service.UploadService;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-2.14 (D14, BD-14; a concurrency class, run 3 times at review points): Cai revokes a fresh token while CI uploads
 * the CI sample with it, 20 times. The project lock serialises the two writes and the upload re-reads the token under
 * it: each upload is 201 with {@code createdAt} not after the token's {@code revokedAt}, or 401 UPLOAD_TOKEN_INVALID
 * with nothing stored; never a 500.
 */
@ReportServiceIntegrationTest
class RevokeRacingUploadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UploadService uploads;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @RepeatedTest(20)
    @Tag("AC-2.14")
    void aRevokeRacingAnUpload() throws Exception {
        String token = ReportData.randomToken();
        UUID tokenId = ReportData.token(jdbc, CHECKOUT, "Race", token, CAI.id(), Instant.now(), null, null);
        CyclicBarrier start = new CyclicBarrier(2);

        CompletableFuture<MvcResult> upload = CompletableFuture.supplyAsync(() -> call(start,
                () -> Uploads.upload(mockMvc, token, "", CiSample.JSON)));
        CompletableFuture<MvcResult> revoke = CompletableFuture.supplyAsync(() -> call(start,
                () -> mockMvc.perform(post("/api/projects/" + CHECKOUT + "/upload-tokens/" + tokenId + "/revoke")
                        .header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn()));
        MvcResult uploaded = upload.get(30, TimeUnit.SECONDS);
        MvcResult revoked = revoke.get(30, TimeUnit.SECONDS);

        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        Instant revokedAt = Instant.parse(JsonPath.read(body(revoked), "$.revokedAt"));
        int runs = jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE upload_token_id = ?", Integer.class,
                tokenId);
        if (uploaded.getResponse().getStatus() == 201) {
            Instant createdAt = Instant.parse(JsonPath.read(body(uploaded), "$.createdAt"));
            assertThat(createdAt).isBeforeOrEqualTo(revokedAt);
            assertThat(runs).isEqualTo(1);
        } else {
            assertProblem(uploaded, 401, "UPLOAD_TOKEN_INVALID");
            assertThat(runs).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results", Integer.class)).isZero();
        }
    }

    @Test
    @Tag("AC-2.14")
    @DisplayName("AC-2.14 A token revoked after authentication is refused under the lock: 401, nothing stored")
    void aTokenRevokedAfterAuthenticationIsRefusedUnderTheLock() {
        String token = ReportData.randomToken();
        Instant now = Instant.now();
        UUID tokenId = ReportData.token(jdbc, CHECKOUT, "Late", token, CAI.id(), now, null, now);

        Throwable failure = catchThrowable(() -> uploads.upload(
                new UploadTokenPrincipal(tokenId, CHECKOUT), new CiUploadQuery(null, null, null, null, null, null),
                new UploadBody("application/json", null, null,
                        new ByteArrayInputStream(CiSample.JSON.getBytes(StandardCharsets.UTF_8)))));

        assertThat(failure).isInstanceOf(UploadTokenInvalidException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM upload_tokens WHERE id = ?", Instant.class, tokenId))
                .isNull();
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
