package org.reportservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AdjustableClock;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.ReportsFixture.User;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, adversarial cases on upload tokens (US-1; D4, D7, BD-17): Unicode names, the {@code expiresAt} boundary on the
 * injected clock, token format and uniqueness, secrecy of list and revoke, IDOR across projects, and the active set.
 */
@ReportServiceIntegrationTest
class QaUploadTokenTest {

    private static final String TOKENS = "/api/projects/" + CHECKOUT + "/upload-tokens";

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @AfterEach
    void restore() {
        ((AdjustableClock) clock).reset();
        stub.reset();
    }

    @ParameterizedTest(name = "AC-1.4 qa: name {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "\"<100 grins>\"      | 201", "\"<101 grins>\"     | 400", "\"\\u200B\"       | 400",
            "\"\\u00A0\"          | 400", "\"\\u3000\"         | 400", "\"\\uFEFF\"         | 400",
            "\"a\\u202Eb\"        | 201", "\"CI\\u0085\"       | 400", "\"CI\\u007F\"       | 400",
            "\"\\uD800\"          | 400", "\"x\\uDC00\"        | 400", "\"Ünïcødé\"         | 201",
            "\"a\\nb\"            | 400", "null                | 400"})
    @Tag("AC-1.4")
    void unicodeNames(String name, int status) throws Exception {
        String json = "{\"name\":" + name.replace("<100 grins>", "\uD83D\uDE00".repeat(100))
                .replace("<101 grins>", "\uD83D\uDE00".repeat(101)) + "}";

        MvcResult result = create(CAI, json);

        if (status == 201) {
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
            String stored = jdbc.queryForObject("SELECT name FROM upload_tokens", String.class);
            assertThat(stored).isEqualTo(JsonPath.read(body(result), "$.name"));
        } else {
            Map<String, Object> problem = assertProblem(result, 400, "VALIDATION_FAILED");
            assertThat((List<Map<String, Object>>) problem.get("errors")).extracting(e -> e.get("field"))
                    .containsExactly("name");
            assertThat(tokenCount()).isZero();
            assertThat(stub.received()).isEmpty();
        }
    }

    @ParameterizedTest(name = "AC-1.4 qa: body {0} -> 400 MALFORMED_REQUEST")
    @CsvSource(delimiter = '|', value = {"[]", "\"x\"", "{\"name\":true}", "{\"name\":[\"x\"]}",
            "{\"name\":\"x\",\"expiresAt\":true}", "{\"name\":\"x\",\"expiresAt\":\"2100-01-01\"}",
            "{\"name\":\"x\",\"expiresAt\":\"2100-01-01T00:00:00\"}", "{\"name\":\"x\""})
    @Tag("AC-1.4")
    void unreadableBodies(String json) throws Exception {
        assertProblem(create(CAI, json), 400, "MALFORMED_REQUEST");
        assertThat(tokenCount()).isZero();
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-1.4")
    @DisplayName("BD-17 qa: expiresAt equal to the clock's now is 400 (before C5); now + 1 µs is 201; offsets become Z")
    void theExpiryMustBeStrictlyInTheFuture() throws Exception {
        AdjustableClock adjustable = (AdjustableClock) clock;
        Instant now = Instant.parse("2031-05-06T07:08:09.123456Z");
        try {
            adjustable.set(now);
            Map<String, Object> problem = assertProblem(create(CAI, "{\"name\":\"x\",\"expiresAt\":\"" + now + "\"}"),
                    400, "VALIDATION_FAILED");
            assertThat((List<Map<String, Object>>) problem.get("errors")).extracting(e -> e.get("field"))
                    .containsExactly("expiresAt");
            assertThat(stub.received()).isEmpty();

            MvcResult ok = create(CAI, "{\"name\":\"x\",\"expiresAt\":\"" + now.plus(1, ChronoUnit.MICROS) + "\"}");
            assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(201);
            assertThat((String) JsonPath.read(body(ok), "$.expiresAt")).isEqualTo("2031-05-06T07:08:09.123457Z");

            MvcResult offset = create(CAI, "{\"name\":\"y\",\"expiresAt\":\"2031-05-06T09:08:10+02:00\"}");
            assertThat(offset.getResponse().getStatus()).as(body(offset)).isEqualTo(201);
            assertThat((String) JsonPath.read(body(offset), "$.expiresAt")).isEqualTo("2031-05-06T07:08:10Z");
        } finally {
            adjustable.reset();
        }
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("AC-1.1 qa: 20 tokens are distinct, decode to 32 bytes, and no header repeats the secret")
    void tokensAreDistinctAndWellFormed() throws Exception {
        Set<String> tokens = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            MvcResult result = create(i % 2 == 0 ? CAI : ANA, "{\"name\":\"t" + i + "\"}");
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            String token = JsonPath.read(body(result), "$.token");
            assertThat(Base64.getUrlDecoder().decode(token.substring(4))).hasSize(32);
            assertThat(token).doesNotContain("=");
            for (String header : result.getResponse().getHeaderNames()) {
                assertThat(result.getResponse().getHeaders(header)).noneMatch(value -> value.contains(token));
            }
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            tokens.add(token);
            prefixes.add(token.substring(0, 12));
            revoke(CAI, UUID.fromString(JsonPath.read(body(result), "$.id")));
        }
        assertThat(tokens).hasSize(20);
        assertThat(prefixes).hasSize(20);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT token_hash) FROM upload_tokens", Integer.class))
                .isEqualTo(20);
        assertThat(jdbc.queryForList("SELECT token_hash FROM upload_tokens", String.class))
                .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    @Test
    @Tag("AC-1.3")
    @DisplayName("AC-1.3 qa: Ana's later revoke keeps Cai's revokedBy and revokedAt; neither response carries the token")
    void aSecondRevokeChangesNothing() throws Exception {
        MvcResult created = create(CAI, "{\"name\":\"x\"}");
        String token = JsonPath.read(body(created), "$.token");
        UUID id = UUID.fromString(JsonPath.read(body(created), "$.id"));

        MvcResult first = revoke(CAI, id);
        MvcResult second = revoke(ANA, id);
        MvcResult list = mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, ANA.bearer())).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(second), "$.revokedBy")).isEqualTo(CAI.id().toString());
        assertThat((String) JsonPath.read(body(second), "$.revokedAt"))
                .isEqualTo(JsonPath.read(body(first), "$.revokedAt"));
        for (MvcResult result : List.of(first, second, list)) {
            assertThat(body(result)).doesNotContain(token, ReportData.sha256Hex(token), "\"token\"", "Hash", "hash");
        }
    }

    @Test
    @Tag("AC-1.8")
    @DisplayName("AC-1.8 qa: revoking Billing's token through Checkout's path is 404 and B1 still uploads into Billing")
    void anotherProjectsTokenIsUntouched() throws Exception {
        String b1 = ReportData.randomToken();
        UUID b1Id = ReportData.token(jdbc, BILLING, "B1", b1, ANA.id(), Instant.now(), null, null);

        assertProblem(revoke(ANA, b1Id), 404, "UPLOAD_TOKEN_NOT_FOUND");
        assertProblem(mockMvc.perform(get("/api/projects/" + BILLING + "/upload-tokens")
                .header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn(), 403, "FORBIDDEN");
        MvcResult upload = Uploads.upload(mockMvc, b1, "", CiSample.JSON);

        assertThat(upload.getResponse().getStatus()).as(body(upload)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM upload_tokens WHERE id = ?", Boolean.class,
                b1Id)).isTrue();
        MvcResult checkoutList = mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, ANA.bearer()))
                .andReturn();
        assertThat(body(checkoutList)).isEqualTo("[]");
    }

    @Test
    @Tag("AC-1.6")
    @DisplayName("AC-1.6 qa: an unknown project is 403; X-User-Id can't raise a reader to admin")
    void noRoleFromHeadersOrUnknownProjects() throws Exception {
        assertProblem(mockMvc.perform(get("/api/projects/" + UUID.randomUUID() + "/upload-tokens")
                .header(HttpHeaders.AUTHORIZATION, ANA.bearer())).andReturn(), 403, "FORBIDDEN");
        assertProblem(mockMvc.perform(post(TOKENS).header(HttpHeaders.AUTHORIZATION, DAN.bearer())
                .header("X-User-Id", ANA.id().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andReturn(), 403, "FORBIDDEN");
        assertThat(tokenCount()).isZero();
    }

    @Test
    @Tag("AC-1.8")
    @DisplayName("AC-6.5 qa: an upper-case token id is 400 MALFORMED_REQUEST and C5 isn't called")
    void strictTokenIds() throws Exception {
        UUID id = ReportData.token(jdbc, CHECKOUT, "T", ReportData.randomToken(), CAI.id(), Instant.now(), null, null);
        MvcResult result = mockMvc.perform(post(TOKENS + "/" + id.toString().toUpperCase() + "/revoke")
                .header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn();
        assertProblem(result, 400, "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-1.5")
    @DisplayName("AC-1.5 qa: a token whose expiry passes frees a slot; revoking an expired token is 200")
    void expiryFreesASlot() throws Exception {
        AdjustableClock adjustable = (AdjustableClock) clock;
        Instant now = Instant.parse("2031-01-01T00:00:00Z");
        try {
            adjustable.set(now);
            UUID expiring = null;
            for (int i = 0; i < 10; i++) {
                Instant expiresAt = i == 0 ? now.plus(1, ChronoUnit.HOURS) : null;
                UUID id = ReportData.token(jdbc, CHECKOUT, "t" + i, ReportData.randomToken(), CAI.id(), now,
                        expiresAt, null);
                expiring = i == 0 ? id : expiring;
            }
            assertProblem(create(CAI, "{\"name\":\"eleventh\"}"), 409, "UPLOAD_TOKEN_LIMIT");

            adjustable.set(now.plus(1, ChronoUnit.HOURS));
            assertThat(create(CAI, "{\"name\":\"eleventh\"}").getResponse().getStatus()).isEqualTo(201);
            assertProblem(create(CAI, "{\"name\":\"twelfth\"}"), 409, "UPLOAD_TOKEN_LIMIT");

            MvcResult revoked = revoke(CAI, expiring);
            assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(body(revoked), "$.revokedAt")).isEqualTo("2031-01-01T01:00:00Z");
        } finally {
            adjustable.reset();
        }
    }

    private MvcResult create(User caller, String json) throws Exception {
        return mockMvc.perform(post(TOKENS).header(HttpHeaders.AUTHORIZATION, caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult revoke(User caller, UUID id) throws Exception {
        return mockMvc.perform(post(TOKENS + "/" + id + "/revoke").header(HttpHeaders.AUTHORIZATION, caller.bearer()))
                .andReturn();
    }

    private int tokenCount() {
        return jdbc.queryForObject("SELECT count(*) FROM upload_tokens", Integer.class);
    }
}
