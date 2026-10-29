package org.reportservice.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Where and how report-service calls auth-service's access check (C5) and project status (C7) (D5, D15): directly
 * (never through the gateway), with a 1 s connect timeout and a 2 s read timeout, no retry and no cache.
 */
@Validated
@ConfigurationProperties(prefix = "app.auth-service")
public record AuthServiceProperties(
        @NotNull(message = "app.auth-service.base-url must be set through the AUTH_SERVICE_URL environment variable")
        URI baseUrl,

        @NotNull
        @DefaultValue("1s")
        Duration connectTimeout,

        @NotNull
        @DefaultValue("2s")
        Duration readTimeout) {
}
