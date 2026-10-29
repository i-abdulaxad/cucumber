package org.apigateway.qa;

import com.jayway.jsonpath.JsonPath;
import org.apigateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.apigateway.qa.QaSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA checks for deny-by-default (D2, A9) and actuator exposure (D7): 401 without a token, the 404 Problem with a valid
 * token, and nothing but {@code GET /actuator/health} exposed.
 */
@DisplayName("QA: unknown paths and management endpoints")
class QaUnknownPathsTest extends GatewayIntegrationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/api", "/api/projects/1", "/h2-console", "/v3/api-docs", "/swagger-ui/index.html",
            "/actuator", "/actuator/env", "/actuator/gateway/routes", "/actuator/health/liveness", "/error", "/favicon.ico"})
    @Tag("AC-2.3")
    @DisplayName("A9: an anonymous request to any unrouted path gets the 401 Problem (route existence is not revealed)")
    void anonymousUnknownPathsGet401(String path) {
        // When
        HttpResponse<String> response = gateway.send("GET", path);

        // Then
        assertProblem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body(), 401, "Unauthorized", path);
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator", "/actuator/", "/actuator/info", "/actuator/mappings", "/actuator/heapdump",
            "/actuator/threaddump", "/actuator/loggers", "/actuator/metrics", "/actuator/prometheus", "/actuator/sbom",
            "/actuator/gateway", "/actuator/gateway/routedefinitions", "/actuator/gateway/globalfilters",
            "/actuator/gateway/routefilters", "/actuator/env/jwt.secret", "/actuator/configprops/jwt", "/actuator/HEALTH",
            "/ACTUATOR/health"})
    @Tag("AC-8.5")
    @DisplayName("AC-8.5: no management endpoint other than health is exposed; each answers the 404 Problem")
    void otherManagementEndpointsAre404Problems(String path) {
        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", path);

        // Then
        assertProblem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body(), 404, "Not Found", path);
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-8.5")
    @DisplayName("AC-8.5: POST /actuator/shutdown is not exposed")
    void shutdownIsNotExposed() {
        // When
        HttpResponse<String> response = gateway.sendWithValidToken("POST", "/actuator/shutdown");

        // Then
        assertThat(response.statusCode()).isEqualTo(404);
    }

    /**
     * BUG-api-gateway-6 (D6). Health sub-paths are answered by the actuator itself with an empty 404, not the D6 Problem.
     * Still deferred: platform-hardening Q3 moved the health sub-paths from gateway FU-1 to FU-5 (Helm probes).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health/ping"})
    @Tag("BUG-api-gateway-6")
    @DisplayName("BUG-api-gateway-6 (D6): health sub-paths with a valid token answer the 404 Problem, not an empty 404")
    @Disabled("BUG-api-gateway-6 deferred: docs/specs/api-gateway.md FU-5 (moved from FU-1 by docs/specs/platform-hardening.md Q3)")
    void healthSubPathsAre404Problems(String path) {
        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", path);

        // Then
        assertProblem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body(), 404, "Not Found", path);
    }

    /**
     * BUG-api-gateway-6 (D2, variant of AC-1.5). {@code /error} was Spring Boot's error controller; with a valid token it
     * answered 500 with Boot's default body ({@code "status":999}). Fixed by platform-hardening T5 (D4, AC-5.4; re-enabled
     * by qa at review point B, 2026-10-29): it is the 404 Problem like any other path that matches no route.
     */
    @Test
    @Tag("BUG-api-gateway-6")
    @Tag("AC-5.4")
    @DisplayName("BUG-api-gateway-6 (D2, variant of AC-1.5): GET /error with a valid token answers the 404 Problem like any other unrouted path")
    void theErrorPathIsAnUnroutedPath() {
        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/error");

        // Then
        assertProblem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body(), 404, "Not Found", "/error");
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-8.4")
    @DisplayName("AC-8.4 / contract: GET /actuator/health is public JSON {\"status\":\"UP\"} with an X-Request-Id header")
    void healthMatchesTheContract() {
        // When
        HttpResponse<String> response = gateway.send("GET", "/actuator/health");

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        assertThat(body).containsExactly(Map.entry("status", "UP"));
        assertThat(response.headers().firstValue("X-Request-Id")).hasValueSatisfying(id -> assertThat(id).matches(QaSupport.REQUEST_ID_PATTERN));
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("json"));
        assertNoStubReceivedARequest();
    }
}
