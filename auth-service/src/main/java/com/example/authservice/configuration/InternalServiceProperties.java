package com.example.authservice.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key other services send as {@code X-Service-Key} on {@code /internal/service/**} (C7, report-service spec D5),
 * set through {@code INTERNAL_SERVICE_KEY}. Empty or unset disables those paths. A plain record, so a bind failure can
 * never print it; {@link InternalServiceKey} checks it after binding. {@link #toString()} never shows the key.
 */
@ConfigurationProperties(prefix = "app")
public record InternalServiceProperties(String internalServiceKey) {

    @Override
    public String toString() {
        return "InternalServiceProperties[internalServiceKey=<redacted>]";
    }
}
