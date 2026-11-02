package org.apigateway.qa;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qa, report-service routing at the gateway (report-service spec D3, GW1, GW2; AC-6.6): {@code POST /api/ci/runs} is
 * the only new public operation, so near-miss, traversal and method variants must not reach any service without a
 * token; an upload token is no gateway credential on user paths; {@code /internal/**} is never routed. Raw socket, so
 * paths arrive exactly as written.
 */
@DisplayName("QA: report-service routes and the CI public operation")
class QaReportServiceRoutesTest extends GatewayIntegrationTest {

    private static final String PROJECT = "3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13";
    private static final String UPLOAD_TOKEN = "Authorization: Bearer rct_Q2hlY2tvdXQgdG9rZW4gZXhhbXBsZSBvbmx5IDMyYnl0";

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0} /api/ci/runs without a token -> 401")
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE", "HEAD"})
    @Tag("AC-6.6")
    void otherMethodsOnTheCiPathNeedAToken(String method) {
        RawHttp.Response response = RawHttp.send(port, method, "/api/ci/runs");

        assertThat(response.status()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = {"/api/ci/runs/", "/API/CI/RUNS", "/api/ci/Runs", "/api/ci/runs.json", "/api/ci/runs%20",
            "/api/ci/runsx", "/api/ci", "/api/ci/", "/api/ci/run"})
    @Tag("AC-6.6")
    void nearMissesNeedAToken(String path) {
        RawHttp.Response response = RawHttp.send(port, "POST", path);

        assertThat(response.status()).as(path).isIn(401, 404);
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = {
            "/api/ci/runs/../../projects/" + PROJECT + "/runs",
            "/api/ci/runs/%2e%2e/%2e%2e/projects/" + PROJECT + "/runs",
            "/api/ci/runs%2F..%2F..%2Fprojects%2F" + PROJECT + "%2Fruns",
            "/api/ci/runs;x=y/../../projects/" + PROJECT + "/upload-tokens",
            "/api/ci/runs/..;/..;/projects/" + PROJECT + "/runs",
            "/api/ci/./runs/../../projects/" + PROJECT + "/runs",
            "/api//ci/runs",
            "/api/ci/runs%00",
            "/api/ci/runs\\..\\..\\projects",
            "/api/ci/runs/%2E%2E/%2E%2E/internal/service/projects/" + PROJECT})
    @Tag("AC-6.6")
    void traversalFromThePublicPathNeverReachesAService(String path) {
        RawHttp.Response response = RawHttp.send(port, "POST", path);

        assertThat(response.status()).as(path).isIn(400, 401, 404);
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-6.6")
    @DisplayName("GW2 qa: POST /api/ci/runs;jsessionid=x without a token is forwarded only as /api/ci/runs, or rejected")
    void matrixParametersOnThePublicPath() {
        RawHttp.Response response = RawHttp.send(port, "POST", "/api/ci/runs;jsessionid=abc");

        if (response.status() == 200) {
            assertThat(REPORT_SERVICE.requests()).singleElement()
                    .satisfies(request -> assertThat(request.path()).isIn("/api/ci/runs", "/api/ci/runs;jsessionid=abc"));
        } else {
            assertThat(response.status()).isIn(400, 401, 404);
            assertNoStubReceivedARequest();
        }
        stubsOtherThan(REPORT_SERVICE).forEach(other -> assertThat(other.requests()).isEmpty());
    }

    @Test
    @Tag("AC-6.6")
    @DisplayName("GW2 qa: a protected path in the query string of the public upload doesn't change the target")
    void aProtectedPathInTheQuery() {
        RawHttp.Response response = RawHttp.send(port, "POST", "/api/ci/runs?next=/api/projects/" + PROJECT + "/runs",
                UPLOAD_TOKEN);

        assertThat(response.status()).isEqualTo(200);
        assertThat(REPORT_SERVICE.requests()).singleElement()
                .satisfies(request -> assertThat(request.path()).isEqualTo("/api/ci/runs"));
    }

    @ParameterizedTest(name = "{0} with an upload token instead of a JWT -> the gateway's 401")
    @ValueSource(strings = {"GET /api/projects/" + PROJECT + "/runs", "POST /api/projects/" + PROJECT + "/runs",
            "GET /api/projects/" + PROJECT + "/upload-tokens", "POST /api/projects/" + PROJECT + "/upload-tokens"})
    @Tag("AC-2.5")
    void anUploadTokenIsNoGatewayCredential(String request) {
        String[] parts = request.split(" ");

        RawHttp.Response response = RawHttp.send(port, parts[0], parts[1], UPLOAD_TOKEN);

        assertThat(response.status()).isEqualTo(401);
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "GET {0} with a valid JWT is never routed")
    @ValueSource(strings = {"/internal/service/projects/" + PROJECT, "/internal/projects/" + PROJECT + "/access",
            "/api/projects/" + PROJECT + "/upload-tokensx", "/api/projects/" + PROJECT + "/runs-x",
            "/api/projects/" + PROJECT + "/x/runs"})
    @Tag("AC-6.6")
    void internalAndLookAlikePathsAreNotRouted(String path) {
        RawHttp.Response response = RawHttp.send(port, "GET", path, "Authorization: " + TestTokens.bearerValid());

        assertThat(response.status()).as(path).isEqualTo(404);
        assertNoStubReceivedARequest();
    }
}
