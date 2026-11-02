package org.apigateway;

import org.apigateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GW4 (report-service spec D3, GW1, GW2): report-service's paths reach report-service unchanged; {@code POST
 * /api/ci/runs} is public, so an upload token (not a JWT) is forwarded unevaluated; any other CI method or path still
 * needs a token; and a look-alike project path is still the gateway's 404.
 */
class ReportServiceRoutesTest extends GatewayIntegrationTest {

    private static final String PROJECT = "3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13";
    private static final String RUN = "9e1c3a5b-7d9f-4b1d-8f3a-5c7e9a1b3d50";
    private static final String UPLOAD_TOKEN = "Bearer rct_Q2hlY2tvdXQgdG9rZW4gZXhhbXBsZSBvbmx5IDMyYnl0";

    @ParameterizedTest(name = "{0} {1} -> report-service")
    @CsvSource({
            "GET,  /api/reports/summary, ",
            "GET,  /api/projects/" + PROJECT + "/runs, page=0&size=2",
            "POST, /api/projects/" + PROJECT + "/runs, ",
            "GET,  /api/projects/" + PROJECT + "/runs/" + RUN + "/results, status=FAILED",
            "POST, /api/projects/" + PROJECT + "/runs/" + RUN + "/complete, ",
            "GET,  /api/projects/" + PROJECT + "/upload-tokens, ",
            "POST, /api/projects/" + PROJECT + "/upload-tokens/" + RUN + "/revoke, ",
            "POST, /api/ci/runs, name=Nightly"
    })
    void reportServicePathsReachReportServiceUnchanged(String method, String path, String query) {
        // When
        gateway.sendWithValidToken(method, query == null ? path : path + "?" + query);

        // Then
        assertThat(REPORT_SERVICE.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(method);
            assertThat(request.path()).isEqualTo(path);
            assertThat(request.query()).isEqualTo(query);
        });
        stubsOtherThan(REPORT_SERVICE).forEach(other -> assertThat(other.requests()).as(other.name()).isEmpty());
    }

    @Test
    @DisplayName("GW2: POST /api/ci/runs with an upload token and no JWT reaches report-service, header unchanged")
    void theCiUploadIsPublicAndForwardsTheUploadToken() {
        // When
        HttpResponse<String> response = gateway.send(gateway.request("/api/ci/runs?name=Nightly")
                .header("Authorization", UPLOAD_TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[]")));

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(REPORT_SERVICE.requests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/ci/runs");
            assertThat(request.headerValues("Authorization")).containsExactly(UPLOAD_TOKEN);
            assertThat(request.body()).isEqualTo("[]");
        });
    }

    @ParameterizedTest(name = "{0} {1} without a token -> the gateway's 401")
    @CsvSource({"GET, /api/ci/runs", "POST, /api/ci/runs/x"})
    void otherCiOperationsNeedAToken(String method, String path) {
        // When
        HttpResponse<String> response = gateway.send(method, path);

        // Then
        assertThat(response.statusCode()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    @Test
    @DisplayName("GW4: GET /api/projects/{id}/runsx with a token is still the gateway's 404")
    void aLookAlikeProjectPathIsNotRouted() {
        // When
        HttpResponse<String> response = gateway.sendWithValidToken("GET", "/api/projects/" + PROJECT + "/runsx");

        // Then
        assertThat(response.statusCode()).isEqualTo(404);
        assertNoStubReceivedARequest();
    }
}
