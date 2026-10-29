package org.reportservice.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key report-service sends as {@code X-Service-Key} to auth-service's C7 (D5), set through
 * {@code INTERNAL_SERVICE_KEY}. A plain record, so a bind failure can never print it; {@link InternalServiceKeyCheck}
 * checks it after binding. {@link #toString()} never shows the key.
 */
@ConfigurationProperties(prefix = "app")
public record InternalServiceProperties(String internalServiceKey) {

    @Override
    public String toString() {
        return "InternalServiceProperties[internalServiceKey=<redacted>]";
    }
}
