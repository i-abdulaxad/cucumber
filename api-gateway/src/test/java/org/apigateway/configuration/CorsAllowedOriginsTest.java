package org.apigateway.configuration;

import org.apigateway.support.GatewayClient;
import org.apigateway.support.GatewayLauncher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Allowed origins come from configuration (D3, D5, AC-6.5).
 */
class CorsAllowedOriginsTest {

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of(false, Optional.of("https://app.example.com"), "https://app.example.com", true),
                Arguments.of(false, Optional.of("https://app.example.com"), "http://localhost:5173", false),
                Arguments.of(false, Optional.of("https://a.example.com,https://b.example.com"), "https://b.example.com", true),
                Arguments.of(false, Optional.empty(), "http://localhost:5173", false),
                Arguments.of(true, Optional.empty(), "http://localhost:5173", true));
    }

    @ParameterizedTest(name = "dev profile {0}, CORS_ALLOWED_ORIGINS {1}, origin {2} -> allowed {3}")
    @MethodSource("configurations")
    @Tag("AC-6.5")
    void allowedOriginsComeFromConfiguration(boolean devProfile, Optional<String> allowedOrigins, String origin,
                                             boolean allowed) {
        // Given
        Map<String, Object> environment = GatewayLauncher.testEnvironment();
        allowedOrigins.ifPresent(origins -> environment.put("CORS_ALLOWED_ORIGINS", origins));
        String[] profiles = devProfile ? new String[]{"dev"} : new String[0];

        try (ConfigurableApplicationContext context = GatewayLauncher.run(environment, profiles)) {
            GatewayClient gateway = new GatewayClient(((WebServerApplicationContext) context).getWebServer().getPort());

            // When
            HttpResponse<String> response = gateway.send(gateway.request("/api/orgs")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .method("OPTIONS", BodyPublishers.noBody()));

            // Then
            if (allowed) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(origin);
            } else {
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }
}
