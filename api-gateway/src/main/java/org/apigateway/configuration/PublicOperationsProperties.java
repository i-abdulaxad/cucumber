package org.apigateway.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateway.security")
public record PublicOperationsProperties(
        @NotEmpty(message = "must list at least one operation")
        List<@Valid PublicOperation> publicOperations
) {

    public record PublicOperation(
            @NotNull(message = "must be set")
            HttpMethod method,

            @NotBlank(message = "must be set")
            String path
    ) {
    }
}
