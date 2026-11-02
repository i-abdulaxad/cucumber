package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reportservice.support.AdjustableClock;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T13 (RE10, BD-17; AC-1.1, AC-1.4): {@code expiresAt} accepts any RFC 3339 offset, is truncated to microseconds before
 * the future-check, the store and the answer, and must then be strictly after the service clock's {@code now}.
 */
@ReportServiceIntegrationTest
class UploadTokenExpiryPrecisionTest {

    private static final String TOKENS = "/api/projects/" + CHECKOUT + "/upload-tokens";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("BD-17 An offset with nanoseconds is stored and shown as UTC to the microsecond, in create and list")
    void anOffsetWithNanosecondsIsTruncatedToMicroseconds() throws Exception {
        MvcResult created = create("2030-01-01T00:00:00.123456789+02:00");

        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(created), "$.expiresAt")).isEqualTo("2029-12-31T22:00:00.123456Z");
        MvcResult listed = mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn();
        List<Map<String, Object>> items = JsonPath.read(body(listed), "$");
        assertThat(items).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("expiresAt", "2029-12-31T22:00:00.123456Z"));
    }

    @Test
    @Tag("AC-1.4")
    @DisplayName("BD-17 now + 500 ns truncates to now, which isn't in the future: 400 VALIDATION_FAILED at expiresAt")
    void lessThanAMicrosecondAfterNowIsNotInTheFuture() throws Exception {
        AdjustableClock adjustable = (AdjustableClock) clock;
        Instant now = Instant.parse("2030-06-01T12:00:00.654321Z");
        try {
            adjustable.set(now);

            MvcResult result = create(now.plusNanos(500).toString());

            Map<String, Object> problem = assertProblem(result, 400, "VALIDATION_FAILED");
            assertThat((List<?>) problem.get("errors")).singleElement()
                    .satisfies(error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo("expiresAt"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens", Integer.class)).isZero();
        } finally {
            adjustable.reset();
        }
    }

    private MvcResult create(String expiresAt) throws Exception {
        return mockMvc.perform(post(TOKENS).header(HttpHeaders.AUTHORIZATION, CAI.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CI\",\"expiresAt\":\"" + expiresAt + "\"}")).andReturn();
    }
}
