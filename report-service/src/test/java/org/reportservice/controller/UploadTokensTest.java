package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.ANA;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.reportservice.support.ReportsFixture.EVE;
import static org.reportservice.support.ReportsFixture.GUS;
import static org.reportservice.support.ReportsFixture.HAL;
import static org.reportservice.support.ReportsFixture.IVY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-1 (T5, D4, D7): upload tokens are created by project ADMINs, shown once, stored only as a hash, listed without
 * secrets, and revoked finally and idempotently; at most 10 are active per project.
 */
@ReportServiceIntegrationTest
class UploadTokensTest {

    private static final String TOKENS = "/api/projects/" + CHECKOUT + "/upload-tokens";

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

    @AfterEach
    void unarchive() {
        ReportsFixture.reset();
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("AC-1.1 A token is shown once and stored only as its lower-case SHA-256 hex")
    void aTokenIsShownOnceAndStoredOnlyAsAHash() throws Exception {
        // When
        MvcResult result = create(CAI, CHECKOUT, "{\"name\":\"GitHub Actions\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> token = JsonPath.read(body(result), "$");
        String text = (String) token.get("token");
        assertThat(text).hasSize(47).startsWith("rct_").matches("rct_[A-Za-z0-9_-]{43}");
        assertThat(token).containsEntry("name", "GitHub Actions").containsEntry("tokenPrefix", text.substring(0, 12))
                .containsEntry("createdBy", CAI.id().toString()).containsEntry("expiresAt", null)
                .containsEntry("lastUsedAt", null).containsEntry("revokedAt", null).containsEntry("revokedBy", null)
                .containsKeys("id", "createdAt");
        assertThat(token.keySet()).containsExactlyInAnyOrder("id", "name", "token", "tokenPrefix", "createdBy",
                "createdAt", "expiresAt", "lastUsedAt", "revokedAt", "revokedBy");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM upload_tokens");
        assertThat(row.get("token_hash")).isEqualTo(ReportData.sha256Hex(text));
        assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains(text));
        assertThat(row.get("id")).hasToString((String) token.get("id"));
    }

    @ParameterizedTest(name = "AC-1.6 {0} {1}: {2}")
    @CsvSource({
            "ana, create, 201", "cai, list, 200", "cai, revoke, 200", "gus, create, 403", "hal, list, 403",
            "dan, revoke, 403", "ivy, list, 403", "eve, create, 403"})
    @Tag("AC-1.6")
    void onlyProjectAdminsManageTokens(String user, String operation, int status) throws Exception {
        UUID t1 = ReportData.token(jdbc, CHECKOUT, "T1", ReportData.randomToken(), CAI.id(), Instant.now(), null, null);
        User caller = switch (user) {
            case "ana" -> ANA;
            case "cai" -> CAI;
            case "gus" -> GUS;
            case "hal" -> HAL;
            case "dan" -> DAN;
            case "ivy" -> IVY;
            default -> EVE;
        };

        MvcResult result = switch (operation) {
            case "create" -> create(caller, CHECKOUT, "{\"name\":\"CI\"}");
            case "list" -> mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, caller.bearer()))
                    .andReturn();
            default -> revoke(caller, t1);
        };

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(status);
        if (status == 403) {
            assertProblem(result, 403, "FORBIDDEN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens WHERE revoked_at IS NOT NULL",
                    Integer.class)).isZero();
        }
    }

    @Test
    @Tag("AC-1.2")
    @DisplayName("AC-1.2 The list is newest first, with prefixes and no token or hash")
    void theListNeverShowsAToken() throws Exception {
        create(CAI, CHECKOUT, "{\"name\":\"GitHub Actions\"}");
        String jenkins = JsonPath.read(body(create(CAI, CHECKOUT, "{\"name\":\"Jenkins\"}")), "$.id");
        revoke(CAI, UUID.fromString(jenkins));

        MvcResult result = mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, ANA.bearer())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<Map<String, Object>> items = JsonPath.read(body(result), "$");
        assertThat(items).extracting(item -> item.get("name")).containsExactly("Jenkins", "GitHub Actions");
        assertThat(items).allSatisfy(item -> assertThat(item).containsKey("tokenPrefix")
                .doesNotContainKeys("token", "tokenHash", "hash", "token_hash"));
        assertThat(items.get(0).get("revokedAt")).isNotNull();
        assertThat(items.get(0)).containsEntry("revokedBy", CAI.id().toString());
        assertThat(body(result)).doesNotContain(jdbc.queryForList("SELECT token_hash FROM upload_tokens",
                String.class).toArray(String[]::new));
    }

    @Test
    @Tag("AC-1.3")
    @DisplayName("AC-1.3 Revoke is final and idempotent: both answers are 200 with the same revokedAt")
    void revokeIsFinalAndIdempotent() throws Exception {
        UUID t1 = ReportData.token(jdbc, CHECKOUT, "T1", ReportData.randomToken(), CAI.id(), Instant.now(), null, null);

        MvcResult first = revoke(CAI, t1);
        MvcResult second = revoke(CAI, t1);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String revokedAt = JsonPath.read(body(first), "$.revokedAt");
        assertThat(revokedAt).isNotNull();
        assertThat((String) JsonPath.read(body(second), "$.revokedAt")).isEqualTo(revokedAt);
        assertThat((String) JsonPath.read(body(second), "$.revokedBy")).isEqualTo(CAI.id().toString());
        assertThat(body(second)).doesNotContain("\"token\"");
    }

    @ParameterizedTest(name = "AC-1.4 body {0}: 400 {1}, nothing stored")
    @CsvSource(delimiter = '|', value = {
            "{\"name\":\"\"}                                      | VALIDATION_FAILED | name",
            "{\"name\":\"   \"}                                   | VALIDATION_FAILED | name",
            "{\"name\":\"\\u00a0\\u200b\"}                        | VALIDATION_FAILED | name",
            "{\"name\":\"<101 × a>\"}                             | VALIDATION_FAILED | name",
            "{\"name\":\"CI\\u0007\"}                             | VALIDATION_FAILED | name",
            "{\"name\":\"CI\\ud800\"}                             | VALIDATION_FAILED | name",
            "{}                                                 | VALIDATION_FAILED | name",
            "{\"name\":42}                                        | MALFORMED_REQUEST | -",
            "{\"name\":\"CI\",\"expiresAt\":\"2020-01-01T00:00:00Z\"} | VALIDATION_FAILED | expiresAt"})
    @Tag("AC-1.4")
    void invalidTokenRequestsStoreNothing(String json, String code, String field) throws Exception {
        String body = json.replace("<101 × a>", "a".repeat(101));

        MvcResult result = create(CAI, CHECKOUT, body);

        Map<String, Object> problem = assertProblem(result, 400, code);
        if (!field.equals("-")) {
            assertThat((List<?>) problem.get("errors")).singleElement()
                    .satisfies(error -> assertThat(((Map<?, ?>) error).get("field")).isEqualTo(field));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_tokens", Integer.class)).isZero();
    }

    @Test
    @Tag("AC-1.4")
    @DisplayName("AC-1.4 Limits are inclusive: 100 code points (supplementary ones count once), a future expiry")
    void theLimitsAreInclusive() throws Exception {
        String name = "😀".repeat(100);
        String expiresAt = Instant.now().plus(Duration.ofDays(30)).toString();

        MvcResult result = create(CAI, CHECKOUT, "{\"name\":\"" + name + "\",\"expiresAt\":\"" + expiresAt + "\"}");

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(result), "$.name")).isEqualTo(name);
        assertThat((String) JsonPath.read(body(result), "$.expiresAt")).isNotNull();
    }

    @Test
    @Tag("AC-1.5")
    @DisplayName("AC-1.5 At most 10 active tokens; revoked and expired ones don't count")
    void atMostTenActiveTokensPerProject() throws Exception {
        Instant now = Instant.now();
        List<UUID> active = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            active.add(ReportData.token(jdbc, CHECKOUT, "A" + i, ReportData.randomToken(), CAI.id(), now, null,
                    null));
        }
        ReportData.token(jdbc, CHECKOUT, "Revoked", ReportData.randomToken(), CAI.id(), now, null, now);
        ReportData.token(jdbc, CHECKOUT, "Expired", ReportData.randomToken(), CAI.id(), now.minusSeconds(7200),
                now.minusSeconds(3600), null);

        assertProblem(create(CAI, CHECKOUT, "{\"name\":\"Eleventh\"}"), 409, "UPLOAD_TOKEN_LIMIT");
        assertThat(revoke(CAI, active.get(0)).getResponse().getStatus()).isEqualTo(200);
        assertThat(create(CAI, CHECKOUT, "{\"name\":\"Eleventh\"}").getResponse().getStatus()).isEqualTo(201);
        assertProblem(create(CAI, CHECKOUT, "{\"name\":\"Twelfth\"}"), 409, "UPLOAD_TOKEN_LIMIT");
    }

    @Test
    @Tag("AC-1.7")
    @DisplayName("AC-1.7 Archived project: create is 409 PROJECT_ARCHIVED; list and revoke are 200")
    void anArchivedProjectKeepsListAndRevoke() throws Exception {
        UUID t1 = ReportData.token(jdbc, CHECKOUT, "T1", ReportData.randomToken(), CAI.id(), Instant.now(), null, null);
        ReportsFixture.archive(CHECKOUT);

        assertProblem(create(CAI, CHECKOUT, "{\"name\":\"CI\"}"), 409, "PROJECT_ARCHIVED");
        assertThat(mockMvc.perform(get(TOKENS).header(HttpHeaders.AUTHORIZATION, CAI.bearer())).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(revoke(CAI, t1).getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "AC-1.8 revoke {0}: 404 UPLOAD_TOKEN_NOT_FOUND")
    @CsvSource({"00000000-0000-4000-8000-000000000000", "B1"})
    @Tag("AC-1.8")
    void unknownTokensAreNotFound(String id) throws Exception {
        UUID b1 = ReportData.token(jdbc, BILLING, "B1", ReportData.randomToken(), ANA.id(), Instant.now(), null, null);
        UUID target = id.equals("B1") ? b1 : UUID.fromString(id);

        assertProblem(revoke(CAI, target), 404, "UPLOAD_TOKEN_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM upload_tokens WHERE id = ?", Instant.class, b1))
                .isNull();
    }

    private MvcResult create(User caller, UUID projectId, String json) throws Exception {
        return mockMvc.perform(post("/api/projects/" + projectId + "/upload-tokens")
                .header(HttpHeaders.AUTHORIZATION, caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult revoke(User caller, UUID tokenId) throws Exception {
        return mockMvc.perform(post(TOKENS + "/" + tokenId + "/revoke").header(HttpHeaders.AUTHORIZATION,
                caller.bearer())).andReturn();
    }
}
