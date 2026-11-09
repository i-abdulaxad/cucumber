package org.apigateway.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank(message = "must be set through the JWT_SECRET environment variable")
        String secret
) {

    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>]";
    }
}
