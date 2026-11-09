package org.scenarioservice.qa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureCalls.Raw;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.scenarioservice.support.TestSettings;
import org.scenarioservice.support.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA (adversarial, D6, D17, AC-7.1, AC-7.13, AC-8.9): JWT edge cases beyond AC-7.1's rows, crafted by hand (header
 * and payload JSON signed with HMAC-SHA512 and the TEST-ONLY secret), and a log sweep over failure paths that AC-8.9's
 * happy flow doesn't take, with the service's and Spring Security's loggers at DEBUG.
 */
@ScenarioServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class QaJwtAndLeakageTest {

    private static final byte[] SECRET = Base64.getDecoder().decode(TestSettings.JWT_SECRET);
    private static final List<String> DEBUG_LOGGERS = List.of("org.scenarioservice", "org.springframework.security");

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @Autowired
    private LoggingSystem loggingSystem;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
    }

    @ParameterizedTest(name = "token with {0}: 401, no access check")
    @ValueSource(strings = {"nbf in the future", "an unknown critical header", "exp one second ago",
            "exp as a word", "exp as a numeric string", "sub with spaces", "sub as a number", "sub in braces",
            "alg HS384", "alg hs512 in lower case", "alg none with the signature kept", "an enc header (JWE)",
            "a signature from the undecoded secret text", "a truncated signature", "no signature part",
            "four parts"})
    @Tag("AC-7.1")
    void craftedTokensAreRejected(String row) throws Exception {
        long now = Instant.now().getEpochSecond();
        String gus = GUS.id().toString();
        String token = switch (row) {
            case "nbf in the future" -> hs512("{\"alg\":\"HS512\"}",
                    "{\"sub\":\"" + gus + "\",\"exp\":" + (now + 3600) + ",\"nbf\":" + (now + 1800) + "}");
            case "an unknown critical header" -> hs512("{\"alg\":\"HS512\",\"crit\":[\"qa\"],\"qa\":1}",
                    claims(gus, now + 3600));
            case "exp one second ago" -> hs512("{\"alg\":\"HS512\"}", claims(gus, now - 1));
            case "exp as a word" -> hs512("{\"alg\":\"HS512\"}", "{\"sub\":\"" + gus + "\",\"exp\":\"soon\"}");
            case "exp as a numeric string" -> hs512("{\"alg\":\"HS512\"}",
                    "{\"sub\":\"" + gus + "\",\"exp\":\"" + (now - 3600) + "\"}");
            case "sub with spaces" -> hs512("{\"alg\":\"HS512\"}", claims(" " + gus + " ", now + 3600));
            case "sub as a number" -> hs512("{\"alg\":\"HS512\"}", "{\"sub\":12345,\"exp\":" + (now + 3600) + "}");
            case "sub in braces" -> hs512("{\"alg\":\"HS512\"}", claims("{" + gus + "}", now + 3600));
            case "alg HS384" -> signed("{\"alg\":\"HS384\"}", claims(gus, now + 3600), "HmacSHA384", SECRET);
            case "alg hs512 in lower case" -> hs512("{\"alg\":\"hs512\"}", claims(gus, now + 3600));
            case "alg none with the signature kept" -> {
                String valid = hs512("{\"alg\":\"HS512\"}", claims(gus, now + 3600));
                yield b64("{\"alg\":\"none\"}") + valid.substring(valid.indexOf('.'));
            }
            case "an enc header (JWE)" -> hs512("{\"alg\":\"dir\",\"enc\":\"A256GCM\"}", claims(gus, now + 3600));
            case "a signature from the undecoded secret text" -> signed("{\"alg\":\"HS512\"}",
                    claims(gus, now + 3600), "HmacSHA512", TestSettings.JWT_SECRET.getBytes(StandardCharsets.US_ASCII));
            case "a truncated signature" -> {
                String valid = hs512("{\"alg\":\"HS512\"}", claims(gus, now + 3600));
                yield valid.substring(0, valid.length() - 4);
            }
            case "no signature part" -> {
                String valid = hs512("{\"alg\":\"HS512\"}", claims(gus, now + 3600));
                yield valid.substring(0, valid.lastIndexOf('.'));
            }
            default -> hs512("{\"alg\":\"HS512\"}", claims(gus, now + 3600)) + ".x";
        };

        MvcResult result = mockMvc.perform(get(features(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "Authorization {0}: 401, no access check")
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearertoken", "Token <valid>", "<valid> without a scheme",
            "Bearer <valid of an unknown user, rejected by C5>"})
    @Tag("AC-7.1")
    void malformedAuthorizationHeaders(String row) throws Exception {
        String valid = GUS.token();
        String header = switch (row) {
            case "Bearer" -> "Bearer";
            case "Bearer " -> "Bearer ";
            case "Bearertoken" -> "Bearer" + valid;
            case "Token <valid>" -> "Token " + valid;
            case "<valid> without a scheme" -> valid;
            default -> "Bearer " + TestTokens.valid(UUID.randomUUID(), "nobody@example.com");
        };

        MvcResult result = mockMvc.perform(get(features(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, header))
                .andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        if (!row.startsWith("Bearer <valid of")) {
            assertThat(stub.received()).isEmpty();
        }
    }

    @Test
    @Tag("AC-7.13")
    @DisplayName("D6: a sub in upper case is canonical; the caller is the lower-case user id, and C5 gets the token")
    void anUpperCaseSubjectIsTheSameUser() throws Exception {
        String token = TestTokens.withSubject(GUS.id().toString().toUpperCase(Locale.ROOT), GUS.email());

        MvcResult result = mockMvc.perform(post(features(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json").content("{\"path\":\"features/a.feature\",\"source\":\"Feature: A\\n\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body(result), "$.createdBy")).isEqualTo(GUS.id().toString());
        assertThat(stub.received()).singleElement().satisfies(received ->
                assertThat(received.authorization()).isEqualTo("Bearer " + token));
    }

    @Test
    @Tag("AC-8.9")
    @DisplayName("D17: failure paths log no token, secret, email, URL or content, even with Spring Security at DEBUG")
    void failurePathsLogNothingSensitive(CapturedOutput output) throws Exception {
        String secretPath = "features/qa-secret-path-7f3.feature";
        String secretText = "Feature: Qa secret feature 7f3\n  Scenario: Qa secret scenario\n    Given qa secret step\n";
        String secretMessage = "qa secret message 7f3";
        String secretTag = "@qa-secret-tag-7f3";
        List<String> tokens = new ArrayList<>(List.of(GUS.token()));
        Map<String, LogLevel> previous = new LinkedHashMap<>();
        int logStart = output.getAll().length();
        try {
            for (String logger : DEBUG_LOGGERS) {
                LoggerConfiguration found = loggingSystem.getLoggerConfiguration(logger);
                previous.put(logger, found == null ? null : found.getConfiguredLevel());
                loggingSystem.setLogLevel(logger, LogLevel.DEBUG);
            }
            UUID id = created(mockMvc, GUS, CHECKOUT, secretPath, secretTag + "\n" + secretText);
            create(mockMvc, GUS, CHECKOUT, "/" + secretPath, secretText);
            create(mockMvc, GUS, CHECKOUT, fields("path", secretPath, "source", secretText, "message",
                    secretMessage + "\u0007"));
            create(mockMvc, GUS, CHECKOUT, secretPath, secretText);
            create(mockMvc, GUS, CHECKOUT, "features/other.feature", secretText + "    | qa secret cell |\n"
                    + "    | a | b |\n");
            save(mockMvc, GUS, CHECKOUT, id, fields("path", secretPath, "source", secretText, "baseVersion", 9,
                    "message", secretMessage));
            save(mockMvc, GUS, CHECKOUT, id, fields("path", secretPath, "source", new Raw("\"" + secretText
                    .replace("\n", "\\n") + "\""), "baseVersion", new Raw("\"1\"")));
            perform(mockMvc, GUS, get(URI.create(features(CHECKOUT) + "?tag=" + secretTag + "%20x")));
            perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + UUID.randomUUID()));
            String expired = TestTokens.expired(GUS.id(), GUS.email());
            tokens.add(expired);
            mockMvc.perform(get(features(CHECKOUT)).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));
            for (int status : new int[]{401, 403, 500}) {
                stub.answer(status, "{\"detail\":\"" + secretMessage + "\"}");
                perform(mockMvc, GUS, get(features(CHECKOUT)));
            }
            stub.answer(200, "{\"projectId\":\"" + CHECKOUT + "\",\"role\":\"" + secretMessage + "\",\"archived\":false}");
            perform(mockMvc, GUS, get(features(CHECKOUT)));
            stub.reset();
            stub.delay(Duration.ofSeconds(3));
            perform(mockMvc, GUS, get(features(CHECKOUT)));
            stub.reset();
            stub.stop();
            perform(mockMvc, GUS, get(features(CHECKOUT)));
        } finally {
            stub.reset();
            previous.forEach(loggingSystem::setLogLevel);
        }

        String log = output.getAll().substring(logStart);
        assertThat(log).as("the sweep produced service log lines").contains("Access check unavailable");
        for (String token : tokens) {
            assertThat(log).doesNotContain(token).doesNotContain(token.substring(token.lastIndexOf('.') + 1));
        }
        assertThat(log).doesNotContain(TestSettings.JWT_SECRET, "Bearer ey", GUS.email(),
                "localhost:" + TestSettings.ACCESS_CHECK_PORT, "/internal/projects");
        // Content: the application's own lines. Spring Security's DEBUG request lines quote the request URL with its
        // query string (a tag value); that's framework logging outside D17 (QA report, risks).
        String applicationLines = String.join("\n", log.lines()
                .filter(line -> !line.contains(" o.s.security.") && !line.contains(" o.s.s.")).toList());
        assertThat(applicationLines).doesNotContain(secretPath, "Qa secret feature", "Qa secret scenario",
                "qa secret step", "qa secret cell", secretMessage, secretTag);
    }

    private static String claims(String subject, long exp) {
        return "{\"sub\":\"" + subject + "\",\"email\":\"gus@example.com\",\"exp\":" + exp + "}";
    }

    private static String hs512(String header, String payload) throws Exception {
        return signed(header, payload, "HmacSHA512", SECRET);
    }

    private static String signed(String header, String payload, String algorithm, byte[] key) throws Exception {
        String content = b64(header) + "." + b64(payload);
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return content + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
