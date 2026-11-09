package org.apigateway;

import org.apigateway.support.GatewayIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GE3 (scenario-service spec D2, GE1): the scenario-service paths under {@code /api/projects/*} reach scenario-service
 * unchanged (the query may be re-encoded and reordered by the gateway, with the same decoded parameters), and every
 * other {@code /api/projects/**} path is still the gateway's 404 with a token.
 */
class ScenarioServiceRoutesTest extends GatewayIntegrationTest {

    private static final String PROJECT = "3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13";
    private static final String FEATURE = "0e4c2a86-9b1d-4f3e-a5c7-d9e1f3a5b7c9";

    @ParameterizedTest(name = "{0} {1} -> scenario-service")
    @CsvSource({
            "GET,  /api/projects/" + PROJECT + "/features",
            "GET,  /api/projects/" + PROJECT + "/features?archived=true&tag=@smoke",
            "POST, /api/projects/" + PROJECT + "/features",
            "GET,  /api/projects/" + PROJECT + "/features/" + FEATURE,
            "PUT,  /api/projects/" + PROJECT + "/features/" + FEATURE,
            "GET,  /api/projects/" + PROJECT + "/features/" + FEATURE + "/test-cases",
            "GET,  /api/projects/" + PROJECT + "/features/" + FEATURE + "/versions/2",
            "POST, /api/projects/" + PROJECT + "/features/" + FEATURE + "/archive",
            "GET,  /api/projects/" + PROJECT + "/tags"
    })
    void scenarioServicePathsReachScenarioServiceUnchanged(String method, String pathAndQuery) {
        // When
        gateway.sendWithValidToken(method, pathAndQuery);

        // Then
        String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
        String query = pathAndQuery.contains("?") ? pathAndQuery.substring(pathAndQuery.indexOf('?') + 1) : null;
        assertThat(SCENARIO_SERVICE.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(method);
            assertThat(request.path()).isEqualTo(path);
            // The gateway may reorder parameters and percent-encode characters such as "@"; the values must not change
            assertThat(parameters(request.query())).isEqualTo(parameters(query));
        });
        stubsOtherThan(SCENARIO_SERVICE).forEach(other -> assertThat(other.requests()).as(other.name()).isEmpty());
    }

    /**
     * The query's decoded parameters, sorted; empty without a query.
     */
    private static List<String> parameters(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(query.split("&")).map(parameter -> URLDecoder.decode(parameter, StandardCharsets.UTF_8))
                .sorted().toList();
    }

    @ParameterizedTest(name = "GET {0} -> the gateway's 404")
    @ValueSource(strings = {"/api/projects/1", "/api/projects/" + PROJECT, "/api/projects/" + PROJECT + "/runsx",
            "/api/projects/" + PROJECT + "/featuresx", "/api/projects/" + PROJECT + "/tags/x"})
    void otherProjectPathsAreStillNotRouted(String path) {
        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", path);

        // Then
        assertThat(response.statusCode()).isEqualTo(404);
        assertNoStubReceivedARequest();
    }
}
