package org.reportservice.configuration;

import org.springframework.stereotype.Component;

/**
 * Stops start-up unless {@code JWT_SECRET} is standard Base64 of at least {@value #MINIMUM_BYTES} bytes (D15, the
 * rule of auth-service and scenario-service for HS512). The message names the setting and never the value.
 */
@Component
public class JwtSecretCheck {

    static final int MINIMUM_BYTES = 64;

    private final byte[] secret;

    public JwtSecretCheck(JwtProperties properties) {
        this.secret = SecretSettings.decode(properties.jwtSecret(), "app.jwtSecret", "JWT_SECRET", MINIMUM_BYTES);
    }

    /** The decoded secret, a copy. */
    public byte[] secret() {
        return secret.clone();
    }
}
