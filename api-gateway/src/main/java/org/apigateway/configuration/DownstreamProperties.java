package org.apigateway.configuration;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gateway.downstream")
public record DownstreamProperties(
        @NotNull(message = "must be set (AUTH_SERVICE_URL)")
        URI authServiceUrl,

        @NotNull(message = "must be set (SCENARIO_SERVICE_URL)")
        URI scenarioServiceUrl,

        @NotNull(message = "must be set (REPORT_SERVICE_URL)")
        URI reportServiceUrl,

        @NotNull(message = "must be set (GATEWAY_CONNECT_TIMEOUT)")
        @DurationMin(millis = 1, message = "must be positive (GATEWAY_CONNECT_TIMEOUT)")
        Duration connectTimeout,

        @NotNull(message = "must be set (GATEWAY_RESPONSE_TIMEOUT)")
        @DurationMin(millis = 1, message = "must be positive (GATEWAY_RESPONSE_TIMEOUT)")
        Duration responseTimeout
) {
}
