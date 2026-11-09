package org.scenarioservice.aop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureCalls;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioFixture;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-8.5 (D17; on the real create endpoint since T8): a call slower than
 * {@code app.logging.slow-call-threshold} is logged at WARN. The threshold is read once at start-up, so each row has
 * its own context (two of the run's cached contexts). WARN is enabled at the default levels, so no level is changed.
 */
@ScenarioServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SlowCallLoggingTest {

    @Nested
    @TestPropertySource(properties = "app.logging.slow-call-threshold=1ms")
    class ThresholdOfOneMillisecond {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @Tag("AC-8.5")
        @DisplayName("AC-8.5 threshold 1ms: the use case is logged once at WARN")
        void aCallSlowerThanTheThresholdIsLoggedAtWarn(CapturedOutput output) throws Exception {
            RequestLog log = callASlowUseCase(mockMvc, output);

            assertThat(log.aspectLines()).filteredOn(line -> line.level().equals("WARN")).singleElement()
                    .satisfies(line -> assertThat(line.message()).matches(
                            "Slow call FeatureServiceImpl\\.create: returned in \\d+ ms \\(threshold 1 ms\\)"));
        }
    }

    @Nested
    @TestPropertySource(properties = "app.logging.slow-call-threshold=1h")
    class ThresholdOfOneHour {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @Tag("AC-8.5")
        @DisplayName("AC-8.5 threshold 1h: the use case writes no WARN line")
        void aCallFasterThanTheThresholdIsNotLoggedAtWarn(CapturedOutput output) throws Exception {
            RequestLog log = callASlowUseCase(mockMvc, output);

            assertThat(log.aspectLines()).noneMatch(line -> line.level().equals("WARN"));
        }
    }

    /**
     * Gus creates Payment: an access check over HTTP and several inserts, so well over 1 ms and far under 1 h.
     */
    private static RequestLog callASlowUseCase(MockMvc mockMvc, CapturedOutput output) throws Exception {
        AccessCheckStub.get().reset();
        UUID projectId = ScenarioFixture.CHECKOUT;
        String path = "features/slow-" + UUID.randomUUID() + ".feature";
        int logStart = output.getAll().length();
        int status = FeatureCalls.create(mockMvc, ScenarioFixture.GUS, projectId, path, GherkinSources.PAYMENT)
                .getResponse().getStatus();
        assertThat(status).isEqualTo(201);
        return RequestLog.since(output, logStart);
    }
}
