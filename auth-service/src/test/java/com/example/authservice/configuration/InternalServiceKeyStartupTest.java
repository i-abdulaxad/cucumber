package com.example.authservice.configuration;

import com.example.authservice.AuthServiceApplication;
import com.example.authservice.support.TestJwt;
import com.example.authservice.support.TestServiceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static com.example.authservice.support.TestDatabase.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * report-service spec AC-8.5 (D5, AE3): a set but unusable {@code INTERNAL_SERVICE_KEY} stops auth-service's start-up
 * with a message that names it and never shows the value; a usable key starts it. Each start binds a free port on
 * {@code 127.0.0.1} (the build's {@code server.address}) and uses the test database's connection settings.
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalServiceKeyStartupTest {

    @ParameterizedTest(name = "AC-8.5 INTERNAL_SERVICE_KEY \"{0}\" stops start-up by name")
    @ValueSource(strings = {"short", "MTYgYnl0ZXMgb2YgVEVTVA==", "${INTERNAL_SERVICE_KEY_QA}", "not base64 !!"})
    @Tag("AC-8.5")
    void anUnusableKeyStopsStartUpByName(String key, CapturedOutput output) {
        int logStart = output.getAll().length();

        Throwable failure = catchThrowable(() -> start(key).close());

        assertThat(failure).as("start-up failure").isNotNull();
        assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains("INTERNAL_SERVICE_KEY"));
        assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(key));
        assertThat(output.getAll().substring(logStart)).doesNotContain(key);
    }

    @Test
    @Tag("AC-8.5")
    @DisplayName("AC-8.5 A usable key starts auth-service and is never logged")
    void aUsableKeyStartsTheService(CapturedOutput output) {
        int logStart = output.getAll().length();

        try (ConfigurableApplicationContext app = start(TestServiceKey.KEY)) {
            assertThat(app.isRunning()).isTrue();
        }
        assertThat(output.getAll().substring(logStart)).doesNotContain(TestServiceKey.KEY);
    }

    private static ConfigurableApplicationContext start(String key) {
        // Command-line arguments take precedence over the environment; port 0 keeps 8081 free
        return new SpringApplicationBuilder(AuthServiceApplication.class)
                .run("--server.port=0", "--spring.profiles.active=", "--app.jwtSecret=" + TestJwt.SECRET,
                        "--app.internal-service-key=" + key,
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return messages;
    }
}
