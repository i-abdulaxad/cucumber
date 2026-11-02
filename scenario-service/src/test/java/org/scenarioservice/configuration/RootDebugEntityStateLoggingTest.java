package org.scenarioservice.configuration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.scenarioservice.aop.RequestLog;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * report-service FU-1 (as RE8 and AC-7.4 there, user decision 2026-10-29; standards §3 Logging): with the root logger
 * at DEBUG, {@code org.hibernate.orm.core} stays at INFO from {@code application.yaml}, so Hibernate's flush never
 * prints "Listing entities" with whole entity state (step texts, the Gherkin source). The flow creates a feature and
 * saves a second version. Log levels are JVM-wide (standards §5), so the root level is set right before the flow and
 * restored in {@code finally}.
 */
@ScenarioServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RootDebugEntityStateLoggingTest {

    private static final String HIBERNATE_CORE = "org.hibernate.orm.core";
    private static final String PATH = "features/checkout/payment.feature";
    private static final Pattern HIBERNATE_CORE_DEBUG =
            Pattern.compile("^\\S+\\s+DEBUG\\s.*\\sorg\\.hibernate\\.orm\\.core\\s+:");

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
    @Tag("FU-1")
    @DisplayName("FU-1 At root DEBUG, org.hibernate.orm.core stays at INFO and no entity state is logged")
    void rootDebugLogsNoEntityState(CapturedOutput output) throws Exception {
        LoggerConfiguration rootFound = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel rootLevel = rootFound == null ? null : rootFound.getConfiguredLevel();
        LogLevel hibernateCoreLevel;
        int logStart = output.getAll().length();
        try {
            // Given
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
            hibernateCoreLevel = loggingSystem.getLoggerConfiguration(HIBERNATE_CORE).getEffectiveLevel();

            // When
            MvcResult created = create(mockMvc, GUS, CHECKOUT, fields("path", PATH, "source", GherkinSources.PAYMENT,
                    "message", "First draft"));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            UUID payment = UUID.fromString(JsonPath.read(body(created), "$.id"));
            assertThat(save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                    GherkinSources.PAYMENT_V2, "baseVersion", 1)).getResponse().getStatus()).isEqualTo(200);
        } finally {
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
        }

        // Then
        assertThat(hibernateCoreLevel).as("effective level of " + HIBERNATE_CORE).isEqualTo(LogLevel.INFO);
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("DEBUG")).as("the flow was logged at DEBUG").isPositive();
        assertThat(log.entries()).as("DEBUG entries of " + HIBERNATE_CORE)
                .noneMatch(line -> HIBERNATE_CORE_DEBUG.matcher(line).find());
        // Step texts and lines of the Gherkin source, in both versions
        List<String> content = List.of("the customer pays by card", "the customer pays by credit card",
                "the customer pays by bank transfer", "the order waits for payment", "a basket with these items",
                "Customers pay for their basket");
        for (String line : log.lines()) {
            assertThat(line).as("a log line contains entity state").doesNotContain("Listing entities");
            for (String value : content) {
                assertThat(line).as("a log line contains a step text or Gherkin source").doesNotContain(value);
            }
        }
    }
}
