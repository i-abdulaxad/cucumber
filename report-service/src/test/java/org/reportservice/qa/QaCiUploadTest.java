package org.reportservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AdjustableClock;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.TestSettings;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, adversarial cases on {@code POST /api/ci/runs} (US-2; D5, D9, D13, BD-15 to BD-18): token presentation, the
 * D13 order of checks, query boundaries, {@code lastUsedAt} only on 201, C7 answers that must fail closed with
 * exactly one request, and the expiry boundary on the injected clock.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class QaCiUploadTest {

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private String t1;

    @BeforeEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        t1 = Uploads.activeToken(jdbc, CHECKOUT);
    }

    @AfterEach
    void restore() {
        ((AdjustableClock) clock).reset();
        ReportsFixture.reset();
        stub.reset();
    }

    // ---------- token presentation (AC-2.4) ----------

    @ParameterizedTest(name = "AC-2.4 qa: {0} -> 401 UPLOAD_TOKEN_INVALID, C7 not called")
    @CsvSource(delimiter = '|', value = {
            "rct_ + 42 characters", "rct_ + 44 characters", "rct_ + 43 with '+'", "rct_ + 43 with '='",
            "RCT_ + valid body", "T1 with one letter's case flipped", "T1 + trailing space", "T1 + trailing tab",
            "two spaces after Bearer", "Token scheme", "X-Upload-Token header only", "token in the query string",
            "JWT of Cai", "Bearer + T1 + T1"})
    @Tag("AC-2.4")
    void presentationVariantsAreRejected(String variant) throws Exception {
        String body43 = t1.substring(4);
        MockHttpServletRequestBuilder request = post(URI.create(Uploads.PATH
                + (variant.equals("token in the query string") ? "?token=" + t1 : "")))
                .contentType(MediaType.APPLICATION_JSON).content(CiSample.JSON);
        String authorization = switch (variant) {
            case "rct_ + 42 characters" -> "Bearer " + t1.substring(0, 46);
            case "rct_ + 44 characters" -> "Bearer " + t1 + "A";
            case "rct_ + 43 with '+'" -> "Bearer rct_+" + body43.substring(1);
            case "rct_ + 43 with '='" -> "Bearer rct_" + body43.substring(1) + "=";
            case "RCT_ + valid body" -> "Bearer RCT_" + body43;
            case "T1 with one letter's case flipped" -> "Bearer " + flipFirstLetter(t1);
            case "T1 + trailing space" -> "Bearer " + t1 + " ";
            case "T1 + trailing tab" -> "Bearer " + t1 + "\t";
            case "two spaces after Bearer" -> "Bearer  " + t1;
            case "Token scheme" -> "Token " + t1;
            case "JWT of Cai" -> CAI.bearer();
            case "Bearer + T1 + T1" -> "Bearer " + t1 + t1;
            default -> null;
        };
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (variant.equals("X-Upload-Token header only")) {
            request.header("X-Upload-Token", "Bearer " + t1);
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertProblem(result, 401, "UPLOAD_TOKEN_INVALID");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(stub.received()).isEmpty();
        assertThat(lastUsedAt()).isNull();
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("AC-2.4 qa: a Billing token uploads into Billing only, and C7 is asked about Billing")
    void aTokenNamesItsProject() throws Exception {
        String b1 = Uploads.activeToken(jdbc, BILLING);

        MvcResult result = Uploads.upload(mockMvc, b1, "", CiSample.JSON);

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(result), "$.projectId")).isEqualTo(BILLING.toString());
        assertThat(jdbc.queryForObject("SELECT project_id FROM test_runs", UUID.class)).isEqualTo(BILLING);
        assertThat(stub.received()).singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/internal/service/projects/" + BILLING));
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("BD-17 qa: an upload one microsecond before expiresAt is 201; at expiresAt it is 401")
    void theExpiryBoundaryIsExclusive() throws Exception {
        AdjustableClock adjustable = (AdjustableClock) clock;
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        String token = ReportData.randomToken();
        ReportData.token(jdbc, CHECKOUT, "expiring", token, CAI.id(), Instant.now(), expiresAt, null);
        try {
            adjustable.set(expiresAt);
            assertProblem(Uploads.upload(mockMvc, token, "", CiSample.JSON), 401, "UPLOAD_TOKEN_INVALID");
            assertThat(Uploads.runs(jdbc)).isZero();

            adjustable.set(expiresAt.minus(1, ChronoUnit.MICROS));
            MvcResult ok = Uploads.upload(mockMvc, token, "", CiSample.JSON);
            assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(201);
        } finally {
            adjustable.reset();
        }
    }

    // ---------- lastUsedAt only on 201 ----------

    @Test
    @Tag("AC-2.6")
    @DisplayName("AC-2.1/AC-2.6 qa: lastUsedAt stays null on 400, 413, 415, 422, 409 and 503, then follows the latest run")
    void lastUsedAtOnlyOnSuccess() throws Exception {
        assertProblem(Uploads.upload(mockMvc, t1, "commit=xyz", CiSample.JSON), 400, "VALIDATION_FAILED");
        assertProblem(Uploads.upload(mockMvc, t1, "", "[{\"uri\":5}]"), 422, "CUCUMBER_JSON_INVALID");
        assertProblem(mockMvc.perform(post(URI.create(Uploads.PATH)).header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)
                .contentType(MediaType.TEXT_PLAIN).content(CiSample.JSON)).andReturn(), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(mockMvc.perform(post(URI.create(Uploads.PATH)).header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)
                .header(HttpHeaders.CONTENT_LENGTH, "30000000").contentType(MediaType.APPLICATION_JSON)
                .content(CiSample.JSON)).andReturn(), 413, "UPLOAD_TOO_LARGE");
        stub.answer(500, "{}");
        assertProblem(Uploads.upload(mockMvc, t1, "", CiSample.JSON), 503, "PROJECT_CHECK_UNAVAILABLE");
        stub.reset();
        ReportsFixture.archive(CHECKOUT);
        assertProblem(Uploads.upload(mockMvc, t1, "environment=qa-env&build=qa-build", CiSample.JSON), 409,
                "PROJECT_ARCHIVED");
        ReportsFixture.reset();
        assertThat(lastUsedAt()).isNull();
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM builds", Integer.class)).isZero();

        Uploads.upload(mockMvc, t1, "", CiSample.JSON);
        MvcResult second = Uploads.upload(mockMvc, t1, "", CiSample.JSON);
        assertThat(lastUsedAt()).isEqualTo(Instant.parse(JsonPath.read(body(second), "$.createdAt")));
    }

    // ---------- order of checks (D13, BD-2) ----------

    @Test
    @Tag("AC-2.9")
    @DisplayName("D13 qa: each pair of failures answers the earlier check, and C7 is called only after the body is valid")
    void theOrderOfChecks() throws Exception {
        assertProblem(mockMvc.perform(post(URI.create(Uploads.PATH)).contentType(MediaType.TEXT_PLAIN)
                .content("x")).andReturn(), 401, "UPLOAD_TOKEN_INVALID");
        assertProblem(mockMvc.perform(post(URI.create(Uploads.PATH)).header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)
                        .header(HttpHeaders.CONTENT_LENGTH, "30000000").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andReturn(), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(mockMvc.perform(post(URI.create(Uploads.PATH + "?commit=xyz"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + t1).header(HttpHeaders.CONTENT_LENGTH, "30000000")
                .contentType(MediaType.APPLICATION_JSON).content("not json")).andReturn(), 413, "UPLOAD_TOO_LARGE");
        assertProblem(Uploads.upload(mockMvc, t1, "commit=xyz", "not json"), 400, "VALIDATION_FAILED");
        ReportsFixture.archive(CHECKOUT);
        assertProblem(Uploads.upload(mockMvc, t1, "", "not json"), 400, "MALFORMED_REQUEST");
        stub.stop();
        assertProblem(Uploads.upload(mockMvc, t1, "", "{\"uri\":\"a\"}"), 422, "CUCUMBER_JSON_INVALID");
        assertProblem(Uploads.upload(mockMvc, t1, "", "[]"), 422, "UPLOAD_EMPTY");
        stub.reset();
        assertThat(stub.received()).isEmpty();
        stub.answer(500, "{}");
        assertProblem(Uploads.upload(mockMvc, t1, "", CiSample.JSON), 503, "PROJECT_CHECK_UNAVAILABLE");
        assertThat(Uploads.runs(jdbc)).isZero();
    }

    // ---------- query parameters (AC-2.10) ----------

    @ParameterizedTest(name = "AC-2.10 qa: {0} is accepted")
    @CsvSource(delimiter = '|', value = {
            "name=<255 astral>", "environment=<100 e>", "build=<255 b>", "branch=<255 r>", "commit=abcd",
            "commit=<64 hex>", "ciUrl=http://x", "ciUrl=https://ci.example.com/<2048>"})
    @Tag("AC-2.10")
    void queryBoundariesAreAccepted(String query) throws Exception {
        MvcResult result = Uploads.upload(mockMvc, t1, expand(query), CiSample.JSON);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
    }

    @ParameterizedTest(name = "AC-2.10 qa: {0} -> 400 for {1}")
    @CsvSource(delimiter = '|', value = {
            "name=<256 astral>          | name", "name=a%09b | name", "name=a%00 | name", "name=%C2%85 | name",
            "name=%E2%80%8B             | name", "environment=<101 e> | environment", "build=<256 b> | build",
            "branch=<256 r>             | branch", "commit=abc | commit", "commit=<65 hex> | commit",
            "commit=ghij                | commit", "ciUrl=javascript:alert(1) | ciUrl", "ciUrl=//host/x | ciUrl",
            "ciUrl=http:/x              | ciUrl", "ciUrl=https://ci.example.com/<2049> | ciUrl",
            "ciUrl=http://exa%20mple.com/ | ciUrl"})
    @Tag("AC-2.10")
    void queryBoundariesAreRejected(String query, String field) throws Exception {
        Map<String, Object> problem = assertProblem(Uploads.upload(mockMvc, t1, expand(query), CiSample.JSON), 400,
                "VALIDATION_FAILED");
        assertThat((List<?>) problem.get("errors")).singleElement()
                .satisfies(error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo(field));
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 qa: every invalid parameter is listed, and no query value is echoed")
    void everyInvalidParameterIsListed() throws Exception {
        Map<String, Object> problem = assertProblem(Uploads.upload(mockMvc, t1,
                "commit=qaMarkerXyz&ciUrl=ftp://qa-marker-host/1&name=%20", CiSample.JSON), 400, "VALIDATION_FAILED");
        assertThat((List<Map<String, Object>>) problem.get("errors")).extracting(error -> error.get("field"))
                .containsExactlyInAnyOrder("ciUrl", "commit", "name");
        assertThat(problem.toString()).doesNotContain("qaMarkerXyz", "qa-marker-host");
    }

    // ---------- C7 answers (AC-2.7, BD-18) ----------

    @ParameterizedTest(name = "AC-2.7 qa: C7 {0} -> 503 after exactly one request")
    @CsvSource(delimiter = '|', value = {"answers 302", "answers 204", "archived is a string", "archived is null",
            "answers an array", "answers 20 KiB", "answers a non-canonical projectId", "answers 200 slowly then 500"})
    @Tag("AC-2.7")
    void oddProjectCheckAnswersFailClosed(String behaviour, CapturedOutput output) throws Exception {
        String ok = AuthServiceStub.statusBody(CHECKOUT);
        switch (behaviour) {
            case "answers 302" -> stub.answer(302, "");
            case "answers 204" -> stub.answer(204, "");
            case "archived is a string" -> stub.answer(200, ok.replace("\"archived\":false", "\"archived\":\"false\""));
            case "archived is null" -> stub.answer(200, ok.replace("\"archived\":false", "\"archived\":null"));
            case "answers an array" -> stub.answer(200, "[" + ok + "]");
            case "answers 20 KiB" -> stub.answer(200, ok.replace("}", ",\"pad\":\"" + "p".repeat(20_480) + "\"}"));
            case "answers a non-canonical projectId" -> stub.answer(200, ok.replace(CHECKOUT.toString(),
                    CHECKOUT.toString().replace("-", "")));
            default -> {
                stub.delay(Duration.ofMillis(1500));
                stub.answer(500, "{}");
            }
        }
        int logStart = output.getAll().length();

        assertProblem(Uploads.upload(mockMvc, t1, "", CiSample.JSON), 503, "PROJECT_CHECK_UNAVAILABLE");

        assertThat(stub.received()).hasSize(1);
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(lastUsedAt()).isNull();
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.entries()).filteredOn(line -> line.contains("Project check unavailable")).hasSize(1);
        assertThat(log.lines()).noneMatch(line -> line.contains(t1) || line.contains(t1.substring(0, 12))
                || line.contains(TestSettings.INTERNAL_SERVICE_KEY) || line.contains("/internal/")
                || line.contains(String.valueOf(TestSettings.AUTH_SERVICE_PORT)));
    }

    @Test
    @Tag("AC-2.7")
    @DisplayName("BD-18 qa: C7 answering the token's project in upper case is the same project; extra members are fine")
    void upperCaseProjectIdIsTheSameProject() throws Exception {
        stub.answer(200, AuthServiceStub.statusBody(CHECKOUT).replace(CHECKOUT.toString(),
                CHECKOUT.toString().toUpperCase()).replace("}", ",\"future\":1}"));

        MvcResult result = Uploads.upload(mockMvc, t1, "", CiSample.JSON);

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
    }

    // ---------- robustness ----------

    @Test
    @Tag("AC-2.12")
    @DisplayName("AC-2.12 qa: a 15 MiB error_message is stored as its first 65,536 code points")
    void aHugeErrorMessageIsTruncated() throws Exception {
        String json = CiSample.JSON.replace("expected rejected but was accepted", "e".repeat(15 * 1024 * 1024));

        MvcResult result = Uploads.upload(mockMvc, t1, "", json);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT max(char_length(error_message)) FROM step_results", Integer.class))
                .isEqualTo(65_536);
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("D13 qa: other methods and sub-paths on /api/ci with a valid token never store a run")
    void otherMethodsOnTheCiPath() throws Exception {
        MvcResult getRuns = mockMvc.perform(get(URI.create(Uploads.PATH))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)).andReturn();
        assertProblem(getRuns, 405, "METHOD_NOT_ALLOWED");
        MvcResult subPath = mockMvc.perform(post(URI.create(Uploads.PATH + "/x"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + t1).contentType(MediaType.APPLICATION_JSON)
                .content(CiSample.JSON)).andReturn();
        assertProblem(subPath, 404, "NOT_FOUND");
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(lastUsedAt()).isNull();
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("AC-2.5 qa: an upload token on the token-management paths is 401 UNAUTHENTICATED, C5 not called")
    void anUploadTokenDoesNotManageTokens() throws Exception {
        String tokens = "/api/projects/" + CHECKOUT + "/upload-tokens";
        assertProblem(mockMvc.perform(get(tokens).header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)).andReturn(),
                401, "UNAUTHENTICATED");
        assertProblem(mockMvc.perform(post(tokens).header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andReturn(), 401,
                "UNAUTHENTICATED");
        assertThat(stub.received()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens", Integer.class)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private Instant lastUsedAt() {
        return jdbc.queryForObject("SELECT last_used_at FROM upload_tokens WHERE token_hash = ?", Instant.class,
                ReportData.sha256Hex(t1));
    }

    private static String flipFirstLetter(String token) {
        StringBuilder flipped = new StringBuilder(token);
        for (int i = 4; i < flipped.length(); i++) {
            char c = flipped.charAt(i);
            if (Character.isLetter(c)) {
                flipped.setCharAt(i, Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
                return flipped.toString();
            }
        }
        throw new IllegalStateException("no letter in the token");
    }

    private static String expand(String query) {
        String grin = "%F0%9F%98%80";
        String base = "https://ci.example.com/";
        return query
                .replace("<255 astral>", grin.repeat(255)).replace("<256 astral>", grin.repeat(256))
                .replace("<100 e>", "e".repeat(100)).replace("<101 e>", "e".repeat(101))
                .replace("<255 b>", "b".repeat(255)).replace("<256 b>", "b".repeat(256))
                .replace("<255 r>", "r".repeat(255)).replace("<256 r>", "r".repeat(256))
                .replace("<64 hex>", "a".repeat(64)).replace("<65 hex>", "a".repeat(65))
                .replace("https://ci.example.com/<2048>", base + "x".repeat(2048 - base.length()))
                .replace("https://ci.example.com/<2049>", base + "x".repeat(2049 - base.length()));
    }
}
