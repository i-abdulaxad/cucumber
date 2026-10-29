package org.scenarioservice.qa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureCalls.Raw;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.json;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA (adversarial, D8, D16, D19 #6, D20 #4; AC-3.10, AC-7.9, AC-7.12, AC-7.14, AC-7.15): one spelling per value. JSON
 * types for every field and every {@code baseVersion} form, raw body edge cases, and odd spellings of path ids,
 * {@code {version}} and {@code archived}. Nothing is stored, and no access check is made, for a malformed request.
 */
@ScenarioServiceIntegrationTest
class QaStrictInputTest {

    private static final String PATH = "features/checkout/payment.feature";

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    private UUID payment;

    @BeforeEach
    void gusHasCreatedPayment() throws Exception {
        data.reset();
        stub.reset();
        payment = created(mockMvc, GUS, CHECKOUT, PATH, GherkinSources.PAYMENT);
        stub.reset();
    }

    @ParameterizedTest(name = "baseVersion {0}: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"\"1\"", "\"\"", "1.0", "1e0", "1E+0", "0.5", "true", "false", "[1]", "{\"v\":1}",
            "2147483648", "-2147483649", "99999999999999999999", "1.5e3"})
    @Tag("AC-7.12")
    void baseVersionMustBeAJsonInteger(String value) throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", new Raw(value)));

        assertProblem(result, 400, "MALFORMED_REQUEST");
        assertUnchanged();
    }

    @ParameterizedTest(name = "baseVersion {0}: 400 VALIDATION_FAILED for baseVersion")
    @ValueSource(strings = {"null", "0", "-1", "-2147483648"})
    @Tag("AC-3.10")
    void baseVersionRange(String value) throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", new Raw(value)));

        assertProblem(result, 400, "VALIDATION_FAILED");
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(body(result), "$.errors[*].field"))
                .contains("baseVersion");
        assertUnchanged();
    }

    @ParameterizedTest(name = "{0}: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"path as an array", "path as an object", "source as an array", "source as an object",
            "message as true", "message as an array", "message as a number", "path as a fraction"})
    @Tag("AC-7.15")
    void stringFieldsRejectOtherJsonTypes(String row) throws Exception {
        Map<String, Object> body = fields("path", "features/address.feature", "source", GherkinSources.ADDRESS);
        switch (row) {
            case "path as an array" -> body.put("path", new Raw("[\"features/address.feature\"]"));
            case "path as an object" -> body.put("path", new Raw("{\"p\":\"features/address.feature\"}"));
            case "source as an array" -> body.put("source", new Raw("[\"Feature: A\"]"));
            case "source as an object" -> body.put("source", new Raw("{}"));
            case "message as true" -> body.put("message", new Raw("true"));
            case "message as an array" -> body.put("message", new Raw("[]"));
            case "message as a number" -> body.put("message", new Raw("7"));
            default -> body.put("path", new Raw("1.5"));
        }

        assertProblem(postJson(json(body)), 400, "MALFORMED_REQUEST");
        assertThat(data.count("features")).isEqualTo(1);
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "body {0}: 400 MALFORMED_REQUEST")
    @ValueSource(strings = {"empty", "null", "an array", "a string", "a number", "truncated", "a trailing comma",
            "a comment", "single quotes", "an unquoted name", "invalid UTF-8", "a leading zero in baseVersion"})
    @Tag("AC-7.9")
    void unreadableBodies(String row) throws Exception {
        byte[] content = switch (row) {
            case "empty" -> new byte[0];
            case "null" -> bytes("null");
            case "an array" -> bytes("[]");
            case "a string" -> bytes("\"x\"");
            case "a number" -> bytes("1");
            case "truncated" -> bytes("{\"path\":\"features/a.feature\",\"source\":\"Feature: A");
            case "a trailing comma" -> bytes("{\"path\":\"features/a.feature\",\"source\":\"Feature: A\",}");
            case "a comment" -> bytes("{/* c */\"path\":\"features/a.feature\",\"source\":\"Feature: A\"}");
            case "single quotes" -> bytes("{'path':'features/a.feature','source':'Feature: A'}");
            case "an unquoted name" -> bytes("{path:\"features/a.feature\",\"source\":\"Feature: A\"}");
            case "invalid UTF-8" -> concat(bytes("{\"path\":\"features/a.feature\",\"source\":\"Feature: "),
                    new byte[]{(byte) 0xC3, (byte) 0x28}, bytes("\"}"));
            default -> bytes("{\"path\":\"features/a.feature\",\"source\":\"Feature: A\",\"baseVersion\":01}");
        };

        MvcResult result = perform(mockMvc, GUS, post(features(CHECKOUT)).contentType(MediaType.APPLICATION_JSON).content(content));

        assertProblem(result, 400, "MALFORMED_REQUEST");
        assertThat(data.count("features")).isEqualTo(1);
    }

    @ParameterizedTest(name = "Content-Type {0}: 415")
    @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded",
            "multipart/form-data; boundary=x"})
    @Tag("AC-7.9")
    void unsupportedMediaTypes(String contentType) throws Exception {
        MvcResult result = perform(mockMvc, GUS, post(features(CHECKOUT)).contentType(contentType)
                .content(json(fields("path", "features/address.feature", "source", GherkinSources.ADDRESS))));

        assertProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(data.count("features")).isEqualTo(1);
    }

    @ParameterizedTest(name = "/versions/{0}: 400 MALFORMED_REQUEST, no access check")
    @ValueSource(strings = {"1.0", "0x1", "%EF%BC%91", "-1", "1e0", "99999999999", "%2B1", "%E2%80%8B1"})
    @Tag("AC-7.12")
    void versionSpellings(String version) throws Exception {
        assertProblem(perform(mockMvc, GUS, get(URI.create(features(CHECKOUT) + "/" + payment + "/versions/" + version))),
                400, "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-3.9")
    @DisplayName("/versions/2147483647 is a valid spelling: 404 VERSION_NOT_FOUND")
    void theLargestVersionIsNotFound() throws Exception {
        assertProblem(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + payment + "/versions/2147483647")), 404,
                "VERSION_NOT_FOUND");
    }

    @ParameterizedTest(name = "archived {0}: 400 MALFORMED_REQUEST, no access check")
    @ValueSource(strings = {"archived=true&archived=true", "archived=true&archived=false", "archived=TRUE",
            "archived=t", "archived=on", "archived=0", "archived=%74rue%00", "archived=true%E2%80%8B"})
    @Tag("AC-7.12")
    void archivedSpellings(String query) throws Exception {
        assertProblem(perform(mockMvc, GUS, get(URI.create(features(CHECKOUT) + "?" + query))), 400,
                "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "project id {0}: 400 MALFORMED_REQUEST, no access check")
    @ValueSource(strings = {"urn:uuid:{id}", "%7B{id}%7D", "{nodashes}", "{id}0", "{fullwidth}", "{id}%E2%80%8B"})
    @Tag("AC-7.14")
    void projectIdSpellings(String template) throws Exception {
        String id = CHECKOUT.toString();
        String spelled = template.replace("{id}", id).replace("{nodashes}", id.replace("-", ""))
                .replace("{fullwidth}", id.replace("-", "%EF%BC%8D"));

        assertProblem(perform(mockMvc, GUS, get(URI.create("/api/projects/" + spelled + "/features"))), 400,
                "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "{0}: rejected with 400 before any access check")
    @ValueSource(strings = {"/api/projects/{id}%00/features", "/api/projects/{id}/features/{fid}/versions/1%00",
            "/api/projects/{id}/features/%2E%2E/{fid}", "/api/projects/{id}/features;x=1",
            "/api/projects/{id}//features"})
    @Tag("AC-7.14")
    void urlsTheFirewallRejects(String template) throws Exception {
        String url = template.replace("{id}", CHECKOUT.toString()).replace("{fid}", payment.toString());

        MvcResult result = perform(mockMvc, GUS, get(URI.create(url)));

        // Spring Security's firewall answers before Spring MVC. MockMvc has no error dispatch, so the body is only checked
        // on a real server: the 400 MALFORMED_REQUEST Problem of platform-hardening AC-4.1 (BUG-5 / SFU-2 fixed by T4)
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(stub.received()).isEmpty();
    }

    private MvcResult postJson(String json) throws Exception {
        return perform(mockMvc, GUS, post(features(CHECKOUT)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void assertUnchanged() throws Exception {
        MvcResult view = perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + payment));
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(body(view), "$.currentVersion")).isEqualTo(1);
        assertThat(data.count("feature_versions")).isEqualTo(1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
