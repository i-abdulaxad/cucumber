package org.apigateway.filter;

import org.apigateway.support.GatewayClient;
import org.apigateway.support.GatewayLauncher;
import org.apigateway.support.StubResponse;
import org.apigateway.support.StubServer;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The access entry names the target service when that service fails (D7, AC-7.8). Each row needs its own
 * scenario-service condition, so each row starts its own gateway.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogDownstreamFailureTest {

    private static final Duration STUB_DELAY = Duration.ofSeconds(5);

    /**
     * The AC-7.8 conditions at the configured scenario-service URL.
     */
    enum DownstreamCondition {

        NOTHING_LISTENING {
            @Override
            void apply(StubServer scenarioService, Map<String, Object> environment) {
                environment.put("SCENARIO_SERVICE_URL", "http://localhost:" + GatewayLauncher.freePort());
            }
        },

        SLOWER_THAN_A_2_SECOND_RESPONSE_TIMEOUT {
            @Override
            void apply(StubServer scenarioService, Map<String, Object> environment) {
                environment.put("GATEWAY_RESPONSE_TIMEOUT", "2s");
                environment.put("SCENARIO_SERVICE_URL", scenarioService.baseUrl());
                scenarioService.respondWith(StubResponse.ok().after(STUB_DELAY));
            }
        },

        CONNECTION_CLOSED_WITHOUT_A_RESPONSE {
            @Override
            void apply(StubServer scenarioService, Map<String, Object> environment) {
                environment.put("SCENARIO_SERVICE_URL", scenarioService.baseUrl());
                scenarioService.respondWith(StubResponse.dropConnection());
            }
        };

        abstract void apply(StubServer scenarioService, Map<String, Object> environment);
    }

    @ParameterizedTest(name = "fail-{0}: {1} -> {2}")
    @CsvSource({
            "1, NOTHING_LISTENING,                       503",
            "2, SLOWER_THAN_A_2_SECOND_RESPONSE_TIMEOUT, 504",
            "3, CONNECTION_CLOSED_WITHOUT_A_RESPONSE,    502"
    })
    @Tag("AC-7.8")
    void theAccessEntryNamesTheTargetServiceWhenThatServiceFails(int n, DownstreamCondition condition, int status,
                                                                 CapturedOutput output) {
        try (StubServer scenarioService = StubServer.start("scenario-service")) {
            // Given
            Map<String, Object> environment = GatewayLauncher.testEnvironment();
            condition.apply(scenarioService, environment);
            String requestId = "fail-" + n;

            try (ConfigurableApplicationContext context = GatewayLauncher.run(environment)) {
                GatewayClient gateway = new GatewayClient(
                        ((WebServerApplicationContext) context).getWebServer().getPort());

                // When
                HttpResponse<String> response = gateway.send(gateway.request("/api/scenarios/42")
                        .header("Authorization", TestTokens.bearerValid())
                        .header("X-Request-Id", requestId)
                        .GET());

                // Then
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(theOnlyAccessEntry(output, requestId))
                        .contains(" status=" + status + " ", " service=scenario-service ");
            }
        }
    }

    /**
     * Waits for the entry (it is written after the response is sent) and checks that there is only one.
     */
    private static String theOnlyAccessEntry(CapturedOutput output, String requestId) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !accessEntries(output, requestId).isEmpty());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> accessEntries(output, requestId).size() == 1);
        return accessEntries(output, requestId).get(0) + " ";
    }

    private static List<String> accessEntries(CapturedOutput output, String requestId) {
        Pattern entry = Pattern.compile(Pattern.quote(AccessLogFilter.LOGGER_NAME)
                + ".*requestId=" + Pattern.quote(requestId) + "(\\s|$)");
        return output.getOut().lines().filter(line -> entry.matcher(line).find()).toList();
    }
}
