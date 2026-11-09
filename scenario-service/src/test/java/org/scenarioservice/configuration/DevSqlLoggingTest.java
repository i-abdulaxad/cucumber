package org.scenarioservice.configuration;

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
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.json;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-8.14 (D14 item 13, D15): with the {@code org.hibernate.SQL} level that {@code application-dev.yaml} sets, read as
 * a resource and applied for this test only (the {@code dev} profile is never activated, so no {@code .env} is
 * loaded), SQL goes through the logging system: correlated, with {@code ?} placeholders and no content.
 */
@ScenarioServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class DevSqlLoggingTest {

    private static final String SQL_LOGGER = "org.hibernate.SQL";
    private static final Pattern ENTRY = Pattern.compile("^\\S+\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s.*");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoggingSystem loggingSystem;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        AccessCheckStub.get().reset();
    }

    @Test
    @Tag("AC-8.14")
    @DisplayName("AC-8.14 Dev SQL lines go through the logging system, correlated and without values")
    void devSqlIsCorrelatedAndWithoutValues(CapturedOutput output) throws Exception {
        LoggerConfiguration found = loggingSystem.getLoggerConfiguration(SQL_LOGGER);
        LogLevel foundLevel = found == null ? null : found.getConfiguredLevel();
        int logStart = output.getAll().length();
        int status;
        try {
            loggingSystem.setLogLevel(SQL_LOGGER, devLevel());
            status = mockMvc.perform(post(features(CHECKOUT)).contentType(MediaType.APPLICATION_JSON)
                            .content(json(fields("path", "features/checkout/payment.feature", "source",
                                    GherkinSources.PAYMENT, "message", "First draft")))
                            .header("X-Request-Id", "req-814").header(HttpHeaders.AUTHORIZATION, GUS.bearer()))
                    .andReturn().getResponse().getStatus();
        } finally {
            loggingSystem.setLogLevel(SQL_LOGGER, foundLevel);
        }

        assertThat(status).isEqualTo(201);
        RequestLog log = RequestLog.since(output, logStart);
        List<List<String>> sqlEntries = sqlEntries(log.lines());
        assertThat(sqlEntries).anySatisfy(entry -> assertThat(String.join(" ", entry)).contains("insert")
                .contains("features"));
        assertThat(sqlEntries).allSatisfy(entry -> {
            assertThat(entry.get(0)).contains("requestId=req-814").contains("userId=" + GUS.id());
            assertThat(String.join(" ", entry)).doesNotContain("features/checkout/payment.feature",
                    "Customers pay for their basket", "First draft");
        });
    }

    @Test
    @Tag("AC-8.14")
    @DisplayName("AC-8.14 application-dev.yaml sets org.hibernate.SQL to DEBUG and holds no secret or show-sql")
    void theDevFileSetsDebugSql() throws Exception {
        assertThat(devLevel()).isEqualTo(LogLevel.DEBUG);
        for (PropertySource<?> source : devFile()) {
            assertThat(String.valueOf(source.getProperty("spring.jpa.show-sql"))).isNotEqualToIgnoringCase("true");
            assertThat(source.getProperty("spring.datasource.password")).isNull();
            assertThat(source.getProperty("app.jwtSecret")).isNull();
        }
    }

    /**
     * Each {@code org.hibernate.SQL} entry: its first line and its indented continuation lines.
     */
    private static List<List<String>> sqlEntries(List<String> lines) {
        List<List<String>> entries = new ArrayList<>();
        List<String> current = null;
        for (String line : lines) {
            if (ENTRY.matcher(line).matches()) {
                current = line.contains(" org.hibernate.SQL ") ? new ArrayList<>(List.of(line)) : null;
                if (current != null) {
                    entries.add(current);
                }
            } else if (current != null) {
                current.add(line);
            }
        }
        return entries;
    }

    private static LogLevel devLevel() throws Exception {
        return devFile().stream().map(source -> source.getProperty("logging.level." + SQL_LOGGER))
                .filter(Objects::nonNull).map(level -> LogLevel.valueOf(level.toString())).findFirst().orElseThrow();
    }

    private static List<PropertySource<?>> devFile() throws Exception {
        return new YamlPropertySourceLoader().load("application-dev.yaml",
                new ClassPathResource("application-dev.yaml"));
    }
}
