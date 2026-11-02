package com.example.authservice.configuration;

import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.HttpCalls;
import com.example.authservice.support.HttpCalls.Answer;
import com.example.authservice.support.RunningAuthService;
import com.example.authservice.support.TestServiceKey;
import com.jayway.jsonpath.JsonPath;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * report-service FU-2 (user decision 2026-11-02, after FU-1; standards §3 Logging): with the root logger at DEBUG, a
 * real server (Tomcat, the real {@code DispatcherServlet} and security chain, see {@link RunningAuthService}) logs no
 * request or response body, no URL with a path secret, no header and no credential. The flow covers every kind of
 * secret auth-service handles: passwords and emails in bodies, the access token in a response and in headers, the
 * invitation token in a response and in a path, and the internal service key in a header. Every captured line is
 * checked. The application is started first, because its start applies {@code application.yaml}'s levels; the root
 * level is then set to DEBUG and restored in {@code finally} (log levels are JVM-wide, standards §5).
 */
@ExtendWith(OutputCaptureExtension.class)
class RootDebugRequestLoggingTest {

    private static final String PASSWORD = "Debug-Secret-Pw-41";
    private static final String ANA = "ana.debug@example.com";
    private static final String FAY = "fay.debug@example.com";

    /** The loggers that log bodies, URLs with path secrets, headers or credentials at DEBUG (FU-2). */
    private static final List<String> REQUEST_LOGGERS = List.of(
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
            "org.springframework.security.web.FilterChainProxy",
            "jdk.internal.httpclient.debug");

    @Test
    @Tag("FU-2")
    @DisplayName("FU-2 At root DEBUG, a real server logs no body, path secret, header or credential")
    void rootDebugLogsNoRequestSecrets(CapturedOutput output) throws Exception {
        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        LoggerConfiguration rootFound = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel rootLevel = rootFound == null ? null : rootFound.getConfiguredLevel();
        List<String> forbidden = new ArrayList<>(List.of(PASSWORD, ANA, FAY, TestServiceKey.KEY, "Listing entities"));
        Map<String, LogLevel> levels = new LinkedHashMap<>();
        int logStart;
        try (RunningAuthService server = RunningAuthService.start("--app.internal-service-key=" + TestServiceKey.KEY)) {
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
                assertThat(http.send("POST", "/api/auth/register", "{\"email\":\"" + ANA + "\",\"password\":\""
                        + PASSWORD + "\",\"fullName\":\"Ana Debug\"}").status()).isEqualTo(201);
                Answer login = http.send("POST", "/api/auth/login",
                        "{\"email\":\"" + ANA + "\",\"password\":\"" + PASSWORD + "\"}");
                assertThat(login.status()).isEqualTo(200);
                String token = JsonPath.read(login.body(), "$.accessToken");
                forbidden.add(token);
                String[] bearer = {"Authorization", "Bearer " + token};
                Answer org = http.send("POST", "/api/orgs", "{\"name\":\"Debug Org\"}", bearer);
                assertThat(org.status()).isEqualTo(201);
                String orgId = JsonPath.read(org.body(), "$.id");
                Answer project = http.send("POST", "/api/orgs/" + orgId + "/projects", "{\"name\":\"Checkout\"}",
                        bearer);
                assertThat(project.status()).isEqualTo(201);
                String projectId = JsonPath.read(project.body(), "$.id");
                Answer invited = http.send("POST", "/api/orgs/" + orgId + "/invitations",
                        "{\"email\":\"" + FAY + "\",\"role\":\"MEMBER\"}", bearer);
                assertThat(invited.status()).isEqualTo(201);
                String invitationToken = JsonPath.read(invited.body(), "$.token");
                forbidden.add(invitationToken);
                assertThat(http.send("POST", "/api/auth/invitations/" + invitationToken + "/accept",
                        "{\"password\":\"" + PASSWORD + "\",\"fullName\":\"Fay Debug\"}").status()).isEqualTo(201);
                assertThat(http.send("GET", "/api/orgs/" + orgId + "/members", null, bearer).status())
                        .isEqualTo(200);
                assertThat(http.send("GET", "/internal/projects/" + projectId + "/access", null, bearer).status())
                        .isEqualTo(200);
                assertThat(http.send("GET", "/internal/service/projects/" + projectId, null,
                        "X-Service-Key", TestServiceKey.KEY).status()).isEqualTo(200);
            } finally {
                loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
            }
            UserRepository users = server.bean(UserRepository.class);
            forbidden.add(users.findByEmail(ANA).orElseThrow().getPasswordHash());
            forbidden.add(users.findByEmail(FAY).orElseThrow().getPasswordHash());
        }

        // Then
        assertThat(levels).as("effective levels at root DEBUG").hasSize(REQUEST_LOGGERS.size())
                .allSatisfy((name, level) -> assertThat(level).as(name).isEqualTo(LogLevel.INFO));
        List<String> lines = Arrays.stream(output.getAll().substring(logStart).split("\\R")).toList();
        assertThat(lines).as("the flow was logged at DEBUG").anyMatch(line -> line.contains(" DEBUG "));
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String value : forbidden) {
                assertThat(lower).as("a log line contains a secret, a body or entity state")
                        .doesNotContain(value.toLowerCase(Locale.ROOT));
            }
        }
    }
}
