package com.example.authservice.configuration;

import com.example.authservice.support.TestServiceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * report-service spec D5, AE3: the service key is optional (unset or empty disables C7), at least 32 bytes of
 * standard Base64 when set, matched exactly, and never shown.
 */
class InternalServiceKeyTest {

    @ParameterizedTest(name = "AC-8.2 an unset key (\"{0}\") matches nothing")
    @NullAndEmptySource
    @Tag("AC-8.2")
    void anUnsetKeyDisablesServiceAuthentication(String unset) {
        InternalServiceKey key = new InternalServiceKey(new InternalServiceProperties(unset));

        assertThat(key.enabled()).isFalse();
        assertThat(key.matches(TestServiceKey.KEY)).isFalse();
        assertThat(key.matches("")).isFalse();
        assertThat(key.matches(null)).isFalse();
    }

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 A set key matches only itself, exactly")
    void aSetKeyMatchesOnlyItself() {
        InternalServiceKey key = new InternalServiceKey(new InternalServiceProperties(TestServiceKey.KEY));

        assertThat(key.enabled()).isTrue();
        assertThat(key.matches(TestServiceKey.KEY)).isTrue();
        assertThat(key.matches(TestServiceKey.OTHER_KEY)).isFalse();
        assertThat(key.matches(TestServiceKey.KEY + " ")).isFalse();
        assertThat(key.matches(" " + TestServiceKey.KEY)).isFalse();
        assertThat(key.matches(TestServiceKey.KEY.toLowerCase())).isFalse();
        assertThat(key.matches(TestServiceKey.KEY.substring(1))).isFalse();
        assertThat(key.matches(null)).isFalse();
    }

    @Test
    @Tag("AC-8.5")
    @DisplayName("AC-8.5 32 bytes is enough; 31 bytes is named and rejected without the value")
    void theMinimumIsThirtyTwoBytes() {
        String bytes32 = Base64.getEncoder().encodeToString(new byte[32]);
        String bytes31 = Base64.getEncoder().encodeToString(new byte[31]);

        assertThat(new InternalServiceKey(new InternalServiceProperties(bytes32)).enabled()).isTrue();
        Throwable failure = catchThrowable(() -> new InternalServiceKey(new InternalServiceProperties(bytes31)));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_SERVICE_KEY")
                .hasMessageContaining("at least 32 bytes");
        assertThat(failure.getMessage()).doesNotContain(bytes31);
    }

    @ParameterizedTest(name = "AC-8.5 \"{0}\" is rejected by name")
    @ValueSource(strings = {"short", "   ", "${INTERNAL_SERVICE_KEY}"})
    @Tag("AC-8.5")
    void anUnusableKeyIsRejectedByName(String value) {
        Throwable failure = catchThrowable(() -> new InternalServiceKey(new InternalServiceProperties(value)));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_SERVICE_KEY");
        if (!value.isBlank()) {
            assertThat(failure.getMessage()).doesNotContain(value);
        }
    }

    @Test
    @Tag("AC-8.5")
    @DisplayName("AC-8.5 Neither the settings record nor the check shows the key")
    void theKeyIsNeverShown() {
        InternalServiceProperties properties = new InternalServiceProperties(TestServiceKey.KEY);

        assertThat(properties.toString()).doesNotContain(TestServiceKey.KEY);
        assertThat(new InternalServiceKey(properties).toString()).doesNotContain(TestServiceKey.KEY);
    }
}
