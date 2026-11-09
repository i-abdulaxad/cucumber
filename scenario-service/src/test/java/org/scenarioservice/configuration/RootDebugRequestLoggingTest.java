package org.scenarioservice.configuration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.HttpCalls;
import org.scenarioservice.support.HttpCalls.Answer;
import org.scenarioservice.support.RunningScenarioService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.json;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * report-service FU-2 (user decision 2026-11-02, after FU-1; standards §3 Logging): with the root logger at DEBUG, a
 * real server (Tomcat, the real {@code DispatcherServlet} and security chain, see {@link RunningScenarioService}) logs
 * no request or response body, no header and no credential. The flow sends Gherkin text in bodies, gets it back in
 * responses, sends the JWT in headers, and forwards it to the access check through the JDK HTTP client. Every captured
 * line is checked. The application is started first, because its start applies {@code application.yaml}'s levels;
 * the root level is then set to DEBUG and restored in {@code finally} (log levels are JVM-wide, standards §5).
 */
@ExtendWith(OutputCaptureExtension.class)
class RootDebugRequestLoggingTest {

    private static final String PATH = "features/checkout/payment.feature";

    /**
     * The loggers that log bodies, URLs with path secrets, headers or credentials at DEBUG (FU-2).
     */
    private static final List<String> REQUEST_LOGGERS = List.of(
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
            "org.springframework.security.web.FilterChainProxy",
            "jdk.internal.httpclient.debug");

    @Test
    @Tag("FU-2")
    @DisplayName("FU-2 At root DEBUG, a real server logs no body, header, credential or Gherkin text")
    void rootDebugLogsNoRequestSecrets(CapturedOutput output) throws Exception {
        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        LoggerConfiguration rootFound = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel rootLevel = rootFound == null ? null : rootFound.getConfiguredLevel();
        AccessCheckStub stub = AccessCheckStub.get();
        stub.reset();
        String features = "/api/projects/" + CHECKOUT + "/features";
        String[] bearer = {"Authorization", "Bearer " + GUS.token()};
        Map<String, LogLevel> levels = new LinkedHashMap<>();
        int logStart;
        try (RunningScenarioService server = RunningScenarioService.start()) {
            HttpCalls http = server.http();
            logStart = output.getAll().length();
            try {
                // Given
                loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
                // A logger that nothing has used yet has no configuration; creating it resolves its effective level
                REQUEST_LOGGERS.forEach(name -> {
                    LoggerFactory.getLogger(name);
                    levels.put(name, loggingSystem.getLoggerConfiguration(name).getEffectiveLevel());
                });

                // When
                Answer created = http.send("POST", features, json(fields("path", PATH, "source",
                        GherkinSources.PAYMENT, "message", "First draft")), bearer);
                assertThat(created.status()).isEqualTo(201);
                String feature = features + "/" + JsonPath.read(created.body(), "$.id");
                assertThat(http.send("PUT", feature, json(fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                        "baseVersion", 1)), bearer).status()).isEqualTo(200);
                assertThat(http.send("GET", feature, null, bearer).status()).isEqualTo(200);
                assertThat(http.send("GET", feature + "/test-cases", null, bearer).status()).isEqualTo(200);
                assertThat(http.send("GET", features, null, bearer).status()).isEqualTo(200);
            } finally {
                loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
                stub.reset();
            }
        }

        // Then
        assertThat(levels).as("effective levels at root DEBUG").hasSize(REQUEST_LOGGERS.size())
                .allSatisfy((name, level) -> assertThat(level).as(name).isEqualTo(LogLevel.INFO));
        List<String> lines = Arrays.stream(output.getAll().substring(logStart).split("\\R")).toList();
        assertThat(lines).as("the flow was logged at DEBUG").anyMatch(line -> line.contains(" DEBUG "));
        // The JWT, the email it carries, and Gherkin text of both versions (step texts, the description, a scenario)
        List<String> forbidden = List.of(GUS.token(), GUS.email(), "Feature: Payment", "Pay by card",
                "the customer pays by card", "the customer pays by credit card", "the customer pays by bank transfer",
                "the order waits for payment", "a basket with these items", "Customers pay for their basket",
                "First draft", "Listing entities");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String value : forbidden) {
                assertThat(lower).as("a log line contains a secret, a body or Gherkin text")
                        .doesNotContain(value.toLowerCase(Locale.ROOT));
            }
        }
    }
}
