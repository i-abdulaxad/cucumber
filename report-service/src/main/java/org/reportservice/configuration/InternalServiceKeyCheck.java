package org.reportservice.configuration;

import org.springframework.stereotype.Component;

/**
 * Stops start-up unless {@code INTERNAL_SERVICE_KEY} is set to standard Base64 of at least {@value #MINIMUM_BYTES}
 * bytes (D5, D15): report-service can't check a CI upload's project without it. The message names the setting and
 * never the value.
 */
@Component
public class InternalServiceKeyCheck {

    static final int MINIMUM_BYTES = 32;

    private final String key;

    public InternalServiceKeyCheck(InternalServiceProperties properties) {
        SecretSettings.decode(properties.internalServiceKey(), "app.internal-service-key", "INTERNAL_SERVICE_KEY",
                MINIMUM_BYTES);
        this.key = properties.internalServiceKey();
    }

    /** The key exactly as configured, for the {@code X-Service-Key} header. Never log it. */
    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return "InternalServiceKeyCheck[key=<redacted>]";
    }
}
