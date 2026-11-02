package org.reportservice.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-2.4 (T7's test first, D4, D5) and AC-1.3's upload part: only an active upload token authenticates a CI upload;
 * everything else is the same 401 UPLOAD_TOKEN_INVALID with {@code WWW-Authenticate: Bearer}, nothing is stored and
 * auth-service is never asked.
 */
@ReportServiceIntegrationTest
class UploadAuthenticationTest {

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @ParameterizedTest(name = "AC-2.4 Authorization {0}: 401 UPLOAD_TOKEN_INVALID, no run")
    @ValueSource(strings = {"(none)", "Bearer", "Bearer rct_<43 random characters>", "Bearer {T2} (revoked)",
            "Bearer {T3} (expired 1 h ago)", "Bearer {token of Cai}", "Basic {T1}", "Bearer {T1} with a space"})
    @Tag("AC-2.4")
    void onlyAnActiveUploadTokenAuthenticates(String authorization) throws Exception {
        Instant now = Instant.now();
        String t1 = Uploads.activeToken(jdbc, CHECKOUT);
        String t2 = ReportData.randomToken();
        ReportData.token(jdbc, CHECKOUT, "T2", t2, CAI.id(), now.minusSeconds(60), null, now.minusSeconds(30));
        String t3 = ReportData.randomToken();
        ReportData.token(jdbc, CHECKOUT, "T3", t3, CAI.id(), now.minusSeconds(7200), now.minusSeconds(3600), null);
        String header = switch (authorization) {
            case "(none)" -> null;
            case "Bearer" -> "Bearer";
            case "Bearer rct_<43 random characters>" -> "Bearer " + ReportData.randomToken();
            case "Bearer {T2} (revoked)" -> "Bearer " + t2;
            case "Bearer {T3} (expired 1 h ago)" -> "Bearer " + t3;
            case "Bearer {token of Cai}" -> CAI.bearer();
            case "Basic {T1}" -> "Basic " + t1;
            default -> "Bearer " + t1 + " ";
        };
        MockHttpServletRequestBuilder request = post(Uploads.PATH).contentType(MediaType.APPLICATION_JSON)
                .content(CiSample.JSON);
        if (header != null) {
            request.header(HttpHeaders.AUTHORIZATION, header);
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertProblem(result, 401, "UPLOAD_TOKEN_INVALID");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "D5 the scheme \"{0}\" with '{T1}' authenticates")
    @ValueSource(strings = {"Bearer", "bearer", "BEARER"})
    @Tag("AC-2.4")
    void theSchemeIsMatchedInAnyLetterCase(String scheme) throws Exception {
        String t1 = Uploads.activeToken(jdbc, CHECKOUT);

        MvcResult result = mockMvc.perform(post(Uploads.PATH).contentType(MediaType.APPLICATION_JSON)
                .content(CiSample.JSON).header(HttpHeaders.AUTHORIZATION, scheme + " " + t1)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-1.3")
    @DisplayName("AC-1.3 After Cai revokes {T1} (twice), a CI upload with it is 401 UPLOAD_TOKEN_INVALID")
    void aRevokedTokenNeverUploadsAgain() throws Exception {
        String t1 = Uploads.activeToken(jdbc, CHECKOUT);
        String id = jdbc.queryForObject("SELECT id::text FROM upload_tokens", String.class);
        for (int i = 0; i < 2; i++) {
            assertThat(mockMvc.perform(post("/api/projects/" + CHECKOUT + "/upload-tokens/" + id + "/revoke")
                    .header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
        }

        assertProblem(Uploads.upload(mockMvc, t1, "", CiSample.JSON), 401, "UPLOAD_TOKEN_INVALID");
        assertThat(Uploads.runs(jdbc)).isZero();
    }
}
