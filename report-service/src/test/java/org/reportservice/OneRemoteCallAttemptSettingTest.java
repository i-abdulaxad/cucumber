package org.reportservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RE6 (a copy of platform-hardening E7): the application sets the JDK HTTP client's attempt limit to one before
 * anything else runs, so a real start-up gets the same single attempt per remote call that the test JVM gets from
 * the build (RE7). The JVM-wide property is cleared for the check and restored afterwards (standards §5).
 */
class OneRemoteCallAttemptSettingTest {

    private static final String ATTEMPT_LIMIT = "jdk.httpclient.redirects.retrylimit";

    @Test
    @Tag("AC-6.3")
    @DisplayName("RE6: start-up sets jdk.httpclient.redirects.retrylimit to 1")
    void startUpLimitsTheHttpClientToOneAttempt() {
        String before = System.getProperty(ATTEMPT_LIMIT);
        try {
            System.clearProperty(ATTEMPT_LIMIT);

            ReportServiceApplication.limitHttpClientToOneAttempt();

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
