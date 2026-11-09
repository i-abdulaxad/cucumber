package org.scenarioservice.aop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.json;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-8 (D17; on the real endpoints since T8): the logging aspect, the MDC filters and the correlation pattern. Log
 * levels are JVM-global (W3): each test sets the levels it needs right before its request and restores the levels it
 * found in {@code finally}. The transaction proof uses the test-only probe, which can log its own commit.
 */
@ScenarioServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OperationalLoggingTest {

    static final String ASPECT_LOGGER = "org.scenarioservice.aop";
    private static final String SECURITY_LOGGER = "org.scenarioservice.security";
    private static final Pattern REQUEST_ID = Pattern.compile("\\[requestId=(\\S+) userId=(\\S+)]");
    private static final Pattern GENERATED_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoggingSystem loggingSystem;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
    }

    @Test
    @Tag("AC-8.1")
    @DisplayName("AC-8.1 A use case logs its entry and exit at DEBUG, with a duration and without arguments")
    void aUseCaseLogsEntryAndExitAtDebug(CapturedOutput output) throws Exception {
        RequestLog log = withLevels(Map.of(ASPECT_LOGGER, LogLevel.DEBUG), output, () -> assertThat(perform(
                createPayment().header("X-Request-Id", "req-81")).getResponse().getStatus()).isEqualTo(201));

        assertThat(log.aspectLines()).filteredOn(line -> line.message().equals("Entering FeatureServiceImpl.create"))
                .singleElement().satisfies(line -> assertThat(line.level()).isEqualTo("DEBUG"));
        assertThat(log.aspectLines()).filteredOn(line -> line.message().startsWith("Leaving FeatureServiceImpl.create"))
                .singleElement().satisfies(line -> {
                    assertThat(line.level()).isEqualTo("DEBUG");
                    assertThat(line.message()).matches("Leaving FeatureServiceImpl\\.create: returned in \\d+ ms");
                });
        assertThat(log.lines()).noneMatch(line -> line.contains("Customers pay for their basket")
                || line.contains("features/checkout/payment.feature") || line.contains("First draft"));
    }

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 At the default level, a successful use case writes no aspect line")
    void aSuccessfulUseCaseWritesNoAspectLineByDefault(CapturedOutput output) throws Exception {
        created(mockMvc, GUS, CHECKOUT, "features/checkout/payment.feature", GherkinSources.PAYMENT);

        RequestLog log = withDefaultLevels(output,
                () -> assertThat(perform(DAN.bearer(), get(features(CHECKOUT))).getResponse().getStatus())
                        .isEqualTo(200));

        assertThat(log.aspectLines()).isEmpty();
    }

    @Test
    @Tag("AC-8.3")
    @DisplayName("AC-8.3 A business error is logged once at INFO, with its code and without content")
    void aBusinessErrorIsLoggedOnceAtInfo(CapturedOutput output) throws Exception {
        RequestLog log = withDefaultLevels(output, () -> assertProblem(perform(post(features(CHECKOUT))
                .contentType(MediaType.APPLICATION_JSON).content(json(fields("path", "features/broken.feature",
                        "source", GherkinSources.STEP_AFTER_EXAMPLES)))), 422, "GHERKIN_INVALID"));

        assertThat(log.aspectLines()).singleElement().satisfies(line -> {
            assertThat(line.level()).isEqualTo("INFO");
            assertThat(line.message()).contains("GHERKIN_INVALID");
        });
        assertThat(log.hasStackTraceLine()).isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains("Then a late step")
                || line.contains("features/broken.feature"));
    }

    @Test
    @Tag("AC-8.6")
    @DisplayName("AC-8.6 Every log line of a request carries its request ID and user ID")
    void everyLineCarriesTheRequestAndUserIds(CapturedOutput output) throws Exception {
        RequestLog log = withLevels(Map.of(ASPECT_LOGGER, LogLevel.DEBUG), output, () -> assertThat(
                perform(get(features(CHECKOUT)).header("X-Request-Id", "req-86")).getResponse().getStatus())
                .isEqualTo(200));

        assertThat(log.entries()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("[requestId=req-86 userId=" + GUS.id() + "]"));
    }

    @ParameterizedTest(name = "AC-8.7 {0}")
    @ValueSource(strings = {"without X-Request-Id", "with X-Request-Id: bad id QA", "with an X-Request-Id of 129 a's"})
    @Tag("AC-8.7")
    @DisplayName("AC-8.7 A missing or invalid request ID is replaced in the logs and never echoed")
    void aMissingOrInvalidRequestIdIsReplaced(String row, CapturedOutput output) throws Exception {
        String sent = switch (row) {
            case "without X-Request-Id" -> null;
            case "with X-Request-Id: bad id QA" -> "bad id QA";
            default -> "a".repeat(129);
        };
        MockHttpServletRequestBuilder request = get(features(CHECKOUT) + "/" + UUID.randomUUID());
        if (sent != null) {
            request.header("X-Request-Id", sent);
        }

        RequestLog log = withLevels(Map.of(ASPECT_LOGGER, LogLevel.DEBUG), output,
                () -> assertThat(assertProblem(perform(request), 404, "FEATURE_NOT_FOUND"))
                        .doesNotContainKey("requestId"));

        List<String> requestIds = log.entries().stream().map(REQUEST_ID::matcher).filter(Matcher::find)
                .map(matcher -> matcher.group(1)).distinct().toList();
        assertThat(log.entries()).isNotEmpty().allSatisfy(line -> assertThat(line).containsPattern(REQUEST_ID));
        assertThat(requestIds).singleElement().satisfies(id -> {
            assertThat(id).matches(GENERATED_UUID);
            assertThat(stub.received()).singleElement()
                    .satisfies(received -> assertThat(received.requestId()).isEqualTo(id));
        });
        if (sent != null) {
            assertThat(log.lines()).noneMatch(line -> line.contains(sent));
        }
    }

    @Test
    @Tag("AC-8.8")
    @DisplayName("AC-8.8 Request correlation is cleared after each request")
    void correlationIsClearedAfterEachRequest(CapturedOutput output) throws Exception {
        withLevels(Map.of(ASPECT_LOGGER, LogLevel.DEBUG), output,
                () -> perform(get(features(CHECKOUT)).header("X-Request-Id", "req-88")));

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        // An anonymous request whose bad token the JWT filter logs at DEBUG, inside the security chain
        RequestLog anonymous = withLevels(Map.of(ASPECT_LOGGER, LogLevel.DEBUG, SECURITY_LOGGER, LogLevel.DEBUG),
                output, () -> assertProblem(perform("Bearer not-a-token", get(features(CHECKOUT))), 401,
                        "UNAUTHENTICATED"));
        assertThat(anonymous.entries()).anySatisfy(line -> assertThat(line).contains("Bearer token rejected")
                        .containsPattern("\\[requestId=" + GENERATED_UUID.pattern() + " userId=-]"))
                .noneMatch(line -> line.contains("req-88") || line.contains(GUS.id().toString()));
    }

    @Test
    @Tag("AC-8.10")
    @DisplayName("AC-8.10 An unavailable access check is logged once at WARN, without a stack trace")
    void anUnavailableAccessCheckIsLoggedOnceAtWarn(CapturedOutput output) throws Exception {
        stub.answer(500, "{}");

        RequestLog log = withDefaultLevels(output,
                () -> assertProblem(perform(get(features(CHECKOUT))), 503, "ACCESS_CHECK_UNAVAILABLE"));

        assertThat(log.entries()).filteredOn(line -> line.contains(" WARN ")).singleElement()
                .satisfies(line -> assertThat(line).contains("Access check unavailable"));
        assertThat(log.count("ERROR")).isZero();
        assertThat(log.hasStackTraceLine()).isFalse();
    }

    @Test
    @DisplayName("T4: the aspect wraps the transaction, so its outcome line comes after the commit (probe)")
    void theAspectWrapsTheTransaction(CapturedOutput output) throws Exception {
        RequestLog log = withLevels(Map.of(ASPECT_LOGGER, LogLevel.DEBUG), output, () -> assertThat(
                perform(post("/api/projects/" + CHECKOUT + "/probe/transactions")).getResponse()
                        .getContentAsString()).contains("true"));

        List<String> lines = log.entries();
        int entering = indexOf(lines, "Entering ProbeService.transactional");
        int committed = indexOf(lines, "Probe transaction committed");
        int leaving = indexOf(lines, "Leaving ProbeService.transactional");
        assertThat(entering).isNotNegative().isLessThan(committed);
        assertThat(committed).isLessThan(leaving);
    }

    private static MockHttpServletRequestBuilder createPayment() {
        return post(features(CHECKOUT)).contentType(MediaType.APPLICATION_JSON).content(json(fields(
                "path", "features/checkout/payment.feature", "source", GherkinSources.PAYMENT,
                "message", "First draft")));
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return perform(GUS.bearer(), request);
    }

    private MvcResult perform(String authorization, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization)).andReturn();
    }

    /**
     * The default levels of application.yaml (none set for these loggers), for the request only.
     */
    private RequestLog withDefaultLevels(CapturedOutput output, Request request) throws Exception {
        Map<String, LogLevel> defaults = new LinkedHashMap<>();
        defaults.put(ASPECT_LOGGER, null);
        defaults.put(SECURITY_LOGGER, null);
        return withLevels(defaults, output, request);
    }

    /**
     * Sets the levels right before the request and restores the levels found, in {@code finally} (W3).
     */
    private RequestLog withLevels(Map<String, LogLevel> levels, CapturedOutput output, Request request)
            throws Exception {
        Map<String, LogLevel> found = new LinkedHashMap<>();
        levels.keySet().forEach(logger -> found.put(logger, configuredLevel(logger)));
        int logStart = output.getAll().length();
        try {
            levels.forEach(loggingSystem::setLogLevel);
            request.run();
        } finally {
            found.forEach(loggingSystem::setLogLevel);
        }
        return RequestLog.since(output, logStart);
    }

    private LogLevel configuredLevel(String logger) {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(logger);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    @FunctionalInterface
    interface Request {
        void run() throws Exception;
    }
}
