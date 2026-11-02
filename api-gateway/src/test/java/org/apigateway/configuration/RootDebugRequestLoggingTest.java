package org.apigateway.configuration;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.StubResponse;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.http.HttpRequest.BodyPublishers;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * report-service FU-2 (user decision 2026-11-02; standards §3 Logging): with the root logger at DEBUG, the gateway
 * logs no request or response body, no URL with a path secret, no header and no credential, neither on the way in nor
 * on the forwarded call. The flow sends a password and an email to login and gets a token back, accepts an invitation
 * by its path token, sends the JWT and a service key header on a forwarded call, and sends and gets back Gherkin text.
 * Every captured line is checked. Log levels are JVM-wide (standards §5): the root level is set right before the flow
 * and restored in {@code finally}.
 */
@ExtendWith(OutputCaptureExtension.class)
class RootDebugRequestLoggingTest extends GatewayIntegrationTest {

    /** The loggers that log bodies, URLs with path secrets, headers or credentials at DEBUG (FU-2). */
    private static final List<String> REQUEST_LOGGERS = List.of(
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
            "org.springframework.security.web.FilterChainProxy",
            "jdk.internal.httpclient.debug");

    private static final String STUB_LOGGER = "com.sun.net.httpserver";
    private static final String PASSWORD = "Gw-Debug-Secret-Pw-17";
    private static final String EMAIL = "gw.debug@example.com";
    private static final String ISSUED_TOKEN = "issued-access-token-Fu2Marker";
    private static final String PATH_TOKEN = "inv-Fu2PathSecretToken";
    private static final String SERVICE_KEY = "service-key-Fu2Marker";
    private static final String GHERKIN = "Feature: Gateway debug\\n  Scenario: Pay by voucher\\n"
            + "    When the customer pays by voucher\\n";

    @Test
    @Tag("FU-2")
    @DisplayName("FU-2 At root DEBUG, the gateway logs no body, path secret, header or credential")
    void rootDebugLogsNoRequestSecrets(CapturedOutput output) {
        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        LoggerConfiguration rootFound = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel rootLevel = rootFound == null ? null : rootFound.getConfiguredLevel();
        Map<String, LogLevel> levels = new LinkedHashMap<>();
        String jwt = TestTokens.valid();
        String features = "/api/projects/c0000000-0000-4000-8000-0000000c4ec0/features";
        int logStart = output.getAll().length();
        try {
            // Given
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
            // A logger that nothing has used yet has no configuration; creating it resolves its effective level
            REQUEST_LOGGERS.forEach(name -> {
                LoggerFactory.getLogger(name);
                levels.put(name, loggingSystem.getLoggerConfiguration(name).getEffectiveLevel());
            });

            // When
            AUTH_SERVICE.respondWith(StubResponse.json(200, "{\"accessToken\":\"" + ISSUED_TOKEN + "\"}"));
            assertThat(gateway.send(gateway.request("/api/auth/login")
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD
                            + "\"}"))).statusCode()).isEqualTo(200);
            AUTH_SERVICE.respondWith(StubResponse.json(201, "{\"user\":{\"email\":\"" + EMAIL + "\"}}"));
            assertThat(gateway.send(gateway.request("/api/auth/invitations/" + PATH_TOKEN + "/accept")
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{\"password\":\"" + PASSWORD + "\",\"fullName\":\"Gw Debug\"}")))
                    .statusCode()).isEqualTo(201);
            AUTH_SERVICE.respondWith(StubResponse.ok());
            assertThat(gateway.send(gateway.request("/api/orgs")
                    .header("Authorization", "Bearer " + jwt)
                    .header("X-Service-Key", SERVICE_KEY)
                    .GET()).statusCode()).isEqualTo(200);
            SCENARIO_SERVICE.respondWith(StubResponse.json(201, "{\"source\":\"" + GHERKIN + "\"}"));
            assertThat(gateway.send(gateway.request(features)
                    .header("Authorization", "Bearer " + jwt)
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{\"path\":\"a.feature\",\"source\":\"" + GHERKIN + "\"}")))
                    .statusCode()).isEqualTo(201);
        } finally {
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
        }

        // Then
        assertThat(levels).as("effective levels at root DEBUG").hasSize(REQUEST_LOGGERS.size())
                .allSatisfy((name, level) -> assertThat(level).as(name).isEqualTo(LogLevel.INFO));
        List<String> lines = Arrays.stream(output.getAll().substring(logStart).split("\\R")).toList();
        assertThat(lines).as("the flow was logged at DEBUG").anyMatch(line -> line.contains(" DEBUG "));
        List<String> forbidden = List.of(PASSWORD, EMAIL, ISSUED_TOKEN, PATH_TOKEN, SERVICE_KEY, jwt,
                "Gateway debug", "Pay by voucher", "the customer pays by voucher");
        // The stubs' own lines are the downstream services' log, not the gateway's: the stubs run on the JDK's
        // com.sun.net.httpserver (StubServer), which the gateway itself never uses
        for (String line : lines.stream().filter(line -> !line.contains(" " + STUB_LOGGER + " ")).toList()) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String value : forbidden) {
                assertThat(lower).as("a log line contains a secret, a body or Gherkin text")
                        .doesNotContain(value.toLowerCase(Locale.ROOT));
            }
        }
    }
}
