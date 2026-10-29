package org.scenarioservice.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.scenarioservice.ScenarioServiceApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * platform-hardening D2 (SFU-1, E7) through the real entry point: {@code main} itself must set the JDK HTTP client's
 * attempt limit before Spring starts. backend-swe's {@code OneAccessCheckAttemptSettingTest} calls the helper directly,
 * so removing the call from {@code main} went unnoticed (qa mutant S1). Here start-up is made to fail fast (a database
 * on a closed loopback port, no web server), and the property is checked afterwards. The JVM-wide property is restored
 * in {@code finally} (standards §5).
 */
@DisplayName("QA platform-hardening D2: main sets the one-attempt limit before the application starts")
class QaStartUpAttemptLimitTest {

    private static final String ATTEMPT_LIMIT = "jdk.httpclient.redirects.retrylimit";

    @Test
    @Tag("AC-1.1")
    @DisplayName("AC-1.1 E7: ScenarioServiceApplication.main sets jdk.httpclient.redirects.retrylimit=1 even if start-up then fails")
    void mainSetsTheAttemptLimitFirst() {
        String before = System.getProperty(ATTEMPT_LIMIT);
        try {
            System.clearProperty(ATTEMPT_LIMIT);

            Throwable startUp = catchThrowable(() -> ScenarioServiceApplication.main(new String[] {
                    "--spring.main.web-application-type=none", "--spring.profiles.active=",
                    "--JWT_SECRET=qa-not-used", "--SCENARIO_DB_URL=jdbc:postgresql://127.0.0.1:1/qa_none",
                    "--SCENARIO_DB_USERNAME=qa", "--SCENARIO_DB_PASSWORD=qa-not-used",
                    "--spring.datasource.hikari.connection-timeout=250",
                    "--spring.datasource.hikari.initialization-fail-timeout=1"}));

            assertThat(startUp).as("start-up fails fast without a database").isNotNull();
            assertThat(System.getProperty(ATTEMPT_LIMIT)).isEqualTo("1");
        } finally {
            if (before == null) {
                System.clearProperty(ATTEMPT_LIMIT);
            } else {
                System.setProperty(ATTEMPT_LIMIT, before);
            }
        }
    }
}
