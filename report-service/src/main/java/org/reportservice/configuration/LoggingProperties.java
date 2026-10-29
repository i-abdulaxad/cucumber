package org.reportservice.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** The logging aspect's settings (D17): on or off, and the slow-call threshold for the WARN line. */
@Validated
@ConfigurationProperties(prefix = "app.logging")
public record LoggingProperties(
        @DefaultValue("true")
        boolean enabled,

        @NotNull(message = "app.logging.slow-call-threshold must be set")
        @DefaultValue("500ms")
        Duration slowCallThreshold) {
}
