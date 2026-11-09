package org.apigateway.configuration;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateway.cors")
public record CorsProperties(
        @NotNull(message = "must be set (CORS_ALLOWED_ORIGINS, may be empty)")
        List<@Pattern(regexp = "^https?://[^/*\\s]+$",
                message = "must be an exact origin such as https://app.example.com, without wildcards or paths (CORS_ALLOWED_ORIGINS)")
                String> allowedOrigins,

        @NotEmpty(message = "must list at least one method")
        List<String> allowedMethods,

        @NotEmpty(message = "must list at least one header")
        List<String> allowedHeaders,

        @NotNull(message = "must be set")
        List<String> exposedHeaders,

        @NotNull(message = "must be set")
        Duration maxAge
) {
}
