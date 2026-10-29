package org.reportservice.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.TestSettings;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AC-7.1 (D15) at unit level: the database check rejects only missing, blank or unresolved values and names each by
 * its variable; the JWT secret and the service key must be standard Base64 of at least 64 and 32 bytes. No message
 * and no {@code toString} contains a value.
 */
class SettingsChecksTest {

    private static final String URL = "jdbc:postgresql://db.example:5432/report_service";

    @Test
    @Tag("AC-7.1")
    @DisplayName("AC-7.1 All three missing database settings are named in one failure")
    void allThreeMissingDatabaseSettingsAreNamedInOneFailure() {
        Throwable failure = catchThrowable(() -> new DatabaseSettingsCheck(new DatabaseProperties(null, "", " \t")));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(
                "spring.datasource.url must be set through the REPORT_DB_URL environment variable; "
                        + "spring.datasource.username must be set through the REPORT_DB_USERNAME "
                        + "environment variable; spring.datasource.password must be set through the "
                        + "REPORT_DB_PASSWORD environment variable");
    }

    @ParameterizedTest(name = "AC-7.1 the password \"{0}\" is rejected by name, without its value")
    @ValueSource(strings = {"", "   ", "\n", "${REPORT_DB_PASSWORD}", "${QA_UNSET}"})
    @Tag("AC-7.1")
    void aBlankOrUnresolvedPasswordIsRejectedByName(String password) {
        Throwable failure = catchThrowable(() -> new DatabaseSettingsCheck(
                new DatabaseProperties(URL, "report_service", password)));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(
                "spring.datasource.password must be set through the REPORT_DB_PASSWORD environment variable");
    }

    @ParameterizedTest(name = "the set password \"{0}\" passes the check")
    @ValueSource(strings = {"Qa$Pw-61", "Pw{QA}61$", "x${A}"})
    void aPasswordThatOnlyContainsDollarOrBracesIsSet(String password) {
        assertThatCode(() -> new DatabaseSettingsCheck(new DatabaseProperties(URL, "report_service", password)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "AC-7.1 JWT_SECRET \"{0}\" is rejected by name, without its value")
    @ValueSource(strings = {"", "  ", "${JWT_SECRET}", "not-base64!QA", "dGhpcnR5LXR3byBieXRlcyBvZiBURVNUIHNlY3JldCE="})
    @Tag("AC-7.1")
    void anUnusableJwtSecretIsRejectedByName(String secret) {
        Throwable failure = catchThrowable(() -> new JwtSecretCheck(new JwtProperties(secret)));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        if (!secret.isBlank()) {
            assertThat(failure.getMessage()).doesNotContain(secret);
        }
    }

    @ParameterizedTest(name = "AC-7.1 INTERNAL_SERVICE_KEY \"{0}\" is rejected by name, without its value")
    @ValueSource(strings = {"", "${INTERNAL_SERVICE_KEY}", "not base64!", "MTYgYnl0ZXMgb2YgVEVTVA==",
            "c2hvcnQ="})
    @Tag("AC-7.1")
    void anUnusableServiceKeyIsRejectedByName(String key) {
        Throwable failure = catchThrowable(() -> new InternalServiceKeyCheck(new InternalServiceProperties(key)));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_SERVICE_KEY");
        if (!key.isBlank()) {
            assertThat(failure.getMessage()).doesNotContain(key);
        }
    }

    @Test
    @Tag("AC-7.1")
    @DisplayName("AC-7.1 The limits are inclusive: 64 bytes for the JWT secret, 32 for the service key")
    void theMinimumLengthsAreAccepted() {
        String bytes64 = Base64.getEncoder().encodeToString(new byte[64]);
        String bytes32 = Base64.getEncoder().encodeToString(new byte[32]);
        String bytes31 = Base64.getEncoder().encodeToString(new byte[31]);

        assertThat(new JwtSecretCheck(new JwtProperties(bytes64)).secret()).hasSize(64);
        assertThat(new InternalServiceKeyCheck(new InternalServiceProperties(bytes32)).key()).isEqualTo(bytes32);
        assertThat(catchThrowable(() -> new InternalServiceKeyCheck(new InternalServiceProperties(bytes31))))
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("D15: no settings record or check shows a value")
    void recordsAndChecksAreRedacted() {
        String key = TestSettings.INTERNAL_SERVICE_KEY;
        String secret = TestSettings.JWT_SECRET;

        assertThat(new DatabaseProperties(URL, "report_service", "Pw-Marker-QA").toString())
                .doesNotContain("db.example", "report_service", "Pw-Marker-QA");
        assertThat(new JwtProperties(secret).toString()).doesNotContain(secret);
        assertThat(new InternalServiceProperties(key).toString()).doesNotContain(key);
        assertThat(new InternalServiceKeyCheck(new InternalServiceProperties(key)).toString()).doesNotContain(key);
        assertThat(new String(new JwtSecretCheck(new JwtProperties(secret)).secret(), StandardCharsets.US_ASCII))
                .startsWith("report-service TEST ONLY");
    }
}
