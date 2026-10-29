package org.reportservice.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared HS512 secret (D15, contract C2), set through {@code JWT_SECRET}. A plain record, so a bind failure can
 * never print it; {@link JwtSecretCheck} checks it after binding. {@link #toString()} never shows the secret.
 */
@ConfigurationProperties(prefix = "app")
public record JwtProperties(String jwtSecret) {

    @Override
    public String toString() {
        return "JwtProperties[jwtSecret=<redacted>]";
    }
}
