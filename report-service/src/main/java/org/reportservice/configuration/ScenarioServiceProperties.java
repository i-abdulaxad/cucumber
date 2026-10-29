package org.reportservice.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Where and how report-service calls scenario-service's public reads for manual-run planning (D6, D15): directly
 * (never through the gateway), with a 1 s connect timeout and a 5 s read timeout, and no retry.
 */
@Validated
@ConfigurationProperties(prefix = "app.scenario-service")
public record ScenarioServiceProperties(
        @NotNull(message = "app.scenario-service.base-url must be set through the SCENARIO_SERVICE_URL environment "
                + "variable")
        URI baseUrl,

        @NotNull
        @DefaultValue("1s")
        Duration connectTimeout,

        @NotNull
        @DefaultValue("5s")
        Duration readTimeout) {
}
