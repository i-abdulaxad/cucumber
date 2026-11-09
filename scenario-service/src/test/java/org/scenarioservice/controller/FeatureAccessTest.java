package org.scenarioservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.scenarioservice.support.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.list;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.view;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.HAL;
import static org.scenarioservice.support.ScenarioFixture.LEGACY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * US-7, AC-7.1 to AC-7.8 (T8, end to end on the real endpoints, against the access check stub).
 */
@ScenarioServiceIntegrationTest
class FeatureAccessTest {

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
    }

    @ParameterizedTest(name = "AC-7.1 Authorization {0}: 401 and no access check")
    @ValueSource(strings = {"(none)", "Basic", "expired", "other secret", "HS256", "no exp", "no sub",
            "sub 1-1-1-1-1"})
    @Tag("AC-7.1")
    @DisplayName("AC-7.1 Requests without a valid token are rejected before any access check")
    void noAccessCheckWithoutAValidToken(String authorization) throws Exception {
        MockHttpServletRequestBuilder request = get(features(CHECKOUT));
        String header = switch (authorization) {
            case "(none)" -> null;
            case "Basic" -> "Basic Z3VzOnNlY3JldA==";
            case "expired" -> "Bearer " + TestTokens.expired(GUS.id(), GUS.email());
            case "other secret" -> "Bearer " + TestTokens.signedWithAnotherSecret(GUS.id(), GUS.email());
            case "HS256" -> "Bearer " + TestTokens.signedWithHs256(GUS.id(), GUS.email());
            case "no exp" -> "Bearer " + TestTokens.withoutExpiration(GUS.id(), GUS.email());
            case "no sub" -> "Bearer " + TestTokens.withoutSubject(GUS.email());
            default -> "Bearer " + TestTokens.withSubject("1-1-1-1-1", GUS.email());
        };
        if (header != null) {
            request.header(HttpHeaders.AUTHORIZATION, header);
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-7.2")
    @DisplayName("AC-7.2 The caller's token and request ID are forwarded to the access check")
    void theTokenAndRequestIdAreForwarded() throws Exception {
        MvcResult result = perform(mockMvc, GUS, get(features(CHECKOUT)).header("X-Request-Id", "req-72"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stub.received()).containsExactly(new AccessCheckStub.Received("GET",
                "/internal/projects/" + CHECKOUT + "/access", GUS.bearer(), "req-72"));
    }

    @ParameterizedTest(name = "AC-7.3 the stub {0}: 503 within 3 seconds, nothing stored")
    @ValueSource(strings = {"is not running", "waits 5 seconds", "answers 500", "answers 404", "answers role OWNER",
            "answers a body that isn't JSON", "answers another projectId"})
    @Tag("AC-7.3")
    void anIncompleteAccessCheckFailsClosed(String failure) throws Exception {
        switch (failure) {
            case "is not running" -> stub.stop();
            case "waits 5 seconds" -> stub.delay(Duration.ofSeconds(5));
            case "answers 500" -> stub.answer(500, "{}");
            case "answers 404" -> stub.answer(404, "");
            case "answers role OWNER" -> stub.answer(200, AccessCheckStub.accessBody(CHECKOUT, "OWNER"));
            case "answers a body that isn't JSON" -> stub.answer(200, "not json");
            default -> stub.answer(200, AccessCheckStub.accessBody(UUID.randomUUID(), "MEMBER"));
        }
        long start = System.nanoTime();

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertProblem(result, 503, "ACCESS_CHECK_UNAVAILABLE");
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @Test
    @Tag("AC-7.3")
    @DisplayName("D4: no retry; a 500 from the access check is exactly one C5 request")
    void aServerErrorIsExactlyOneAccessCheckRequest() throws Exception {
        stub.answer(500, "{}");

        assertProblem(list(mockMvc, GUS, CHECKOUT), 503, "ACCESS_CHECK_UNAVAILABLE");
        assertThat(stub.received()).hasSize(1);
    }

    @Test
    @Tag("AC-7.4")
    @DisplayName("AC-7.4 A token that auth-service rejects is rejected here too")
    void aTokenAuthServiceRejectsIsRejected() throws Exception {
        stub.rejectToken(GUS.id());

        MvcResult result = list(mockMvc, GUS, CHECKOUT);

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    @Tag("AC-7.5")
    @DisplayName("AC-7.5 Role changes apply on the next request (no caching)")
    void roleChangesApplyOnTheNextRequest() throws Exception {
        created(mockMvc, GUS, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);

        stub.role(GUS.id(), CHECKOUT, "READER");
        assertProblem(create(mockMvc, GUS, CHECKOUT, "features/other.feature", GherkinSources.ADDRESS), 403,
                "FORBIDDEN");
        stub.role(GUS.id(), CHECKOUT, null);
        assertProblem(list(mockMvc, GUS, CHECKOUT), 403, "FORBIDDEN");
    }

    @ParameterizedTest(name = "AC-7.6 project id {0}")
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "truncated", "unknown", "upper case"})
    @Tag("AC-7.6")
    void projectIds(String row) throws Exception {
        String id = switch (row) {
            case "truncated" -> CHECKOUT.toString().substring(0, 35);
            case "unknown" -> UUID.randomUUID().toString();
            case "upper case" -> CHECKOUT.toString().toUpperCase(Locale.ROOT);
            default -> row;
        };

        MvcResult result = perform(mockMvc, GUS, get("/api/projects/" + id + "/features"));

        switch (row) {
            case "unknown" -> assertProblem(result, 403, "FORBIDDEN");
            case "upper case" -> {
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                assertThat(stub.received()).singleElement().satisfies(received -> assertThat(received.path())
                        .isEqualTo("/internal/projects/" + CHECKOUT + "/access"));
            }
            default -> {
                assertProblem(result, 400, "MALFORMED_REQUEST");
                assertThat(stub.received()).isEmpty();
            }
        }
    }

    @ParameterizedTest(name = "AC-7.7 {0}")
    @ValueSource(strings = {"Gus lists", "Gus views", "Gus creates", "Dan creates"})
    @Tag("AC-7.7")
    void anArchivedProjectIsReadOnly(String row) throws Exception {
        stub.answer(200, AccessCheckStub.accessBody(LEGACY, "MEMBER").replace("\"archived\":true",
                "\"archived\":false"));
        UUID feature = created(mockMvc, GUS, LEGACY, "features/address.feature", GherkinSources.ADDRESS);
        stub.reset();

        MvcResult result = switch (row) {
            case "Gus lists" -> list(mockMvc, GUS, LEGACY);
            case "Gus views" -> view(mockMvc, GUS, LEGACY, feature);
            case "Gus creates" -> create(mockMvc, GUS, LEGACY, "features/new.feature", GherkinSources.PAYMENT);
            default -> create(mockMvc, DAN, LEGACY, "features/new.feature", GherkinSources.PAYMENT);
        };

        switch (row) {
            case "Gus creates" -> assertProblem(result, 409, "PROJECT_ARCHIVED");
            case "Dan creates" -> assertProblem(result, 403, "FORBIDDEN");
            default -> assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }
        Map<String, Object> legacy = JsonPath.read(body(view(mockMvc, GUS, LEGACY, feature)), "$");
        assertThat(legacy).containsEntry("currentVersion", 1).containsEntry("archivedAt", null);
        assertThat(data.count("features")).isEqualTo(1);
    }

    @Test
    @Tag("AC-7.8")
    @DisplayName("AC-7.8 Identity headers from the client are ignored")
    void identityHeadersAreIgnored() throws Exception {
        MvcResult result = perform(mockMvc, HAL, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(features(CHECKOUT)).contentType("application/json")
                .content("{\"path\":\"features/address.feature\",\"source\":\"Feature: A\\n\"}")
                .header("X-User-Id", GUS.id().toString()));

        assertProblem(result, 403, "FORBIDDEN");
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }
}
