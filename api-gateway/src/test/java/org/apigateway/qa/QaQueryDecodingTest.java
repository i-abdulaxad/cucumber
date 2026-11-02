package org.apigateway.qa;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qa, fix round 1 (QA-4, GW6): malformed query percent-encoding on every kind of path the gateway sees. Raw socket, so
 * the bytes arrive as written. Never a 500, never forwarded, never an ERROR line, and the marker in the parameter
 * name or value never reaches the log or the body; the 401 (protected, no token) and 404 (unrouted) answers keep
 * their order.
 */
@DisplayName("QA: malformed query encoding at the gateway (QA-4)")
@ExtendWith(OutputCaptureExtension.class)
class QaQueryDecodingTest extends GatewayIntegrationTest {

    private static final String PROJECT = "3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13";
    private static final String MARKER = "QaLeakMarker67";

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0} {1} [{2}] -> {3}")
    @CsvSource({
            "GET,  /api/projects/" + PROJECT + "/upload-tokens?x=QaLeakMarker67%FF,      jwt,   400",
            "GET,  /api/projects/" + PROJECT + "/runs/x/results?status=QaLeakMarker67%C3%28, jwt, 400",
            "GET,  /api/orgs?q=QaLeakMarker67%ED%A0%80,                                   jwt,   400",
            "GET,  /api/reports/x?QaLeakMarker67%FF=1,                                    jwt,   400",
            "GET,  /api/projects/" + PROJECT + "/tags?QaLeakMarker67=%C0%AF,             jwt,   400",
            "POST, /api/ci/runs?name=QaLeakMarker67%E0%A4,                                none,  400",
            "POST, /api/auth/register?x=QaLeakMarker67%FF,                                none,  400",
            "GET,  /api/ci/runs?name=QaLeakMarker67%FF,                                   none,  401",
            "GET,  /api/projects/" + PROJECT + "/runs?page=QaLeakMarker67%FF,            none,  401",
            "GET,  /api/projects/" + PROJECT + "/runs?page=QaLeakMarker67%FF,            bad,   401",
            "GET,  /internal/service/projects/" + PROJECT + "?x=QaLeakMarker67%FF,      jwt,   404",
            "GET,  /api/unknown?x=QaLeakMarker67%FF,                                      jwt,   404"})
    @Tag("AC-6.6")
    void malformedEncodingEverywhere(String method, String target, String credential, int status,
                                     CapturedOutput output) {
        int logStart = output.getAll().length();
        String[] headers = switch (credential) {
            case "jwt" -> new String[]{"Authorization: " + TestTokens.bearerValid()};
            case "bad" -> new String[]{"Authorization: Bearer " + TestTokens.expired()};
            default -> new String[0];
        };

        RawHttp.Response response = RawHttp.send(port, method, target, headers);

        assertThat(response.status()).as(target).isEqualTo(status);
        assertThat(response.body()).doesNotContain(MARKER).doesNotContain("Exception");
        if (status == 400) {
            assertThat(response.body()).contains("\"detail\":\"The request query could not be decoded.\"")
                    .doesNotContain("\"code\"");
        }
        assertNoStubReceivedARequest();
        String log = output.getAll().substring(logStart);
        assertThat(log).doesNotContain(MARKER).doesNotContain(" ERROR ").doesNotContain("Character decoding");
    }

    @ParameterizedTest(name = "POST /api/ci/runs?{0} -> 400 or the gateway's own 400, never 500, not forwarded")
    @CsvSource({"name=QaLeakMarker67%", "name=QaLeakMarker67%G1", "name=QaLeakMarker67%F", "%FF%FE=QaLeakMarker67"})
    @Tag("AC-6.6")
    void brokenEscapes(String query, CapturedOutput output) {
        int logStart = output.getAll().length();

        RawHttp.Response response = RawHttp.send(port, "POST", "/api/ci/runs?" + query,
                "Authorization: Bearer rct_Q2hlY2tvdXQgdG9rZW4gZXhhbXBsZSBvbmx5IDMyYnl0");

        assertThat(response.status()).as(query).isEqualTo(400);
        assertThat(response.body()).doesNotContain(MARKER);
        assertNoStubReceivedARequest();
        assertThat(output.getAll().substring(logStart)).doesNotContain(MARKER).doesNotContain(" ERROR ");
    }
}
