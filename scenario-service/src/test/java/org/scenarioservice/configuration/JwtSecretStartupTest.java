package org.scenarioservice.configuration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.ScenarioServiceApplication;
import org.scenarioservice.support.ScratchDatabase;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AC-8.12, {@code JWT_SECRET} rows (D6, D20 #2): a missing or unusable secret stops start-up, naming the setting and
 * never showing the value. Each start uses a scratch database with valid settings, no profile (so no {@code .env})
 * and no process environment.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtSecretStartupTest {

    /**
     * The standard Base64 of 32 bytes: too short for HS512.
     */
    private static final String SHORT_SECRET = Base64.getEncoder().encodeToString(
            "thirty-two bytes of TEST secret!".getBytes(StandardCharsets.US_ASCII));

    @ParameterizedTest(name = "AC-8.12 JWT_SECRET {0}")
    @ValueSource(strings = {"unset", "empty", "not Base64", "32 bytes"})
    @Tag("AC-8.12")
    void aMissingOrUnusableSecretStopsStartUpByName(String row, CapturedOutput output) throws Exception {
        String value = switch (row) {
            case "unset" -> null;
            case "empty" -> "";
            case "not Base64" -> "not-base64!QA";
            default -> SHORT_SECRET;
        };
        List<String> expected = switch (row) {
            case "unset", "empty" -> List.of("JWT_SECRET");
            case "not Base64" -> List.of("app.jwtSecret", "Base64");
            default -> List.of("app.jwtSecret", "64");
        };
        try (ScratchDatabase scratch = ScratchDatabase.create()) {
            List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=",
                    "--SCENARIO_DB_URL=" + scratch.url(), "--SCENARIO_DB_USERNAME=" + scratch.username(),
                    "--SCENARIO_DB_PASSWORD=" + scratch.password()));
            if (value != null) {
                arguments.add("--JWT_SECRET=" + value);
            }
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            int logStart = output.getAll().length();

            // When
            Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(ScenarioServiceApplication.class)
                    .environment(environment).run(arguments.toArray(String[]::new)).close());

            // Then
            assertThat(failure).as("start-up failure").isNotNull();
            assertThat(messagesOf(failure)).anySatisfy(message -> assertThat(message).contains(expected));
            if (value != null && !value.isEmpty()) {
                assertThat(messagesOf(failure)).noneSatisfy(message -> assertThat(message).contains(value));
                assertThat(output.getAll().substring(logStart)).doesNotContain(value);
            }
        }
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return messages;
    }
}
