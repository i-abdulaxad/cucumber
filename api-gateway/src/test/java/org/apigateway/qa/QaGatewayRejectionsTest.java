package org.apigateway.qa;

import org.apigateway.support.GatewayIntegrationTest;
import org.apigateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.apigateway.qa.QaSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA adversarial checks of platform-hardening US-5 (D3, D4, D5, D6) at the gateway: more firewall forms than the AC rows,
 * content negotiation, client request IDs, TRACE with credentials and cookies, other unsupported methods, and direct
 * {@code /error} variants. Every request goes over a raw socket (paths exactly as sent) and nothing may be forwarded.
 * Shares the plain {@link GatewayIntegrationTest} context.
 */
@DisplayName("QA platform-hardening US-5: gateway-level rejections are D6 Problems")
class QaGatewayRejectionsTest extends GatewayIntegrationTest {

    private static final String URL_REJECTED = "The request URL was rejected.";
    private static final String METHOD_NOT_SUPPORTED = "This method is not supported for this path.";
    private static final String NO_ROUTE = "No API route matches this path.";
    private static final String TOKEN_REQUIRED = "A valid bearer token is required.";

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST, /api/auth/login;jsessionid=abc, no token",
            "GET,  /api/orgs/%2e%2e/x,             valid token",
            "GET,  /api/orgs/./x,                  valid token",
            "GET,  //api/orgs,                     valid token",
            "GET,  /api/orgs/%252e%252e/x,         valid token",
            "GET,  /api/orgs/%3Bx,                 valid token",
            "GET,  /api/scenarios/42/..;/x,        valid token"})
    @Tag("AC-5.1")
    @DisplayName("AC-5.1 (more rows): firewall rejections are 400 D6 Problems with instance = the path as sent")
    void moreFirewallFormsAreD6Problems(String method, String path, String auth) {
        RawHttp.Response response = auth.equals("valid token")
                ? RawHttp.send(port, method, path, "Authorization: " + TestTokens.bearerValid())
                : RawHttp.send(port, method, path);

        assertD6(response, 400, "Bad Request", path, URL_REJECTED);
        assertThat(response.body()).doesNotContain(TestTokens.valid());
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/html", "application/xml", "text/plain", "*/*;q=0"})
    @Tag("AC-5.1")
    @DisplayName("D3: a firewall rejection is the 400 D6 Problem whatever the client accepts")
    void theProblemIgnoresTheAcceptHeader(String accept) {
        RawHttp.Response response = RawHttp.send(port, "POST", "/api/auth/./login", "Accept: " + accept);

        assertD6(response, 400, "Bad Request", "/api/auth/./login", URL_REJECTED);
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-5.1")
    @DisplayName("D5 item 5: a valid client X-Request-Id is the Problem's requestId; an invalid one is replaced, never echoed")
    void requestIdsOnRejections() {
        RawHttp.Response valid = RawHttp.send(port, "GET", "/api/orgs//x", "X-Request-Id: qa-ph-rid-1");
        RawHttp.Response invalid = RawHttp.send(port, "GET", "/api/orgs//x", "X-Request-Id: qa bad<id>");

        assertD6(valid, 400, "Bad Request", "/api/orgs//x", URL_REJECTED);
        assertThat(valid.headerValues("X-Request-Id")).containsExactly("qa-ph-rid-1");
        assertD6(invalid, 400, "Bad Request", "/api/orgs//x", URL_REJECTED);
        assertThat(invalid.headerValues("X-Request-Id")).singleElement().satisfies(id ->
                assertThat(id).matches(QaSupport.UUID_PATTERN));
        assertThat(invalid.body()).doesNotContain("qa bad", "<id>");
    }

    @ParameterizedTest(name = "TRACE {0}")
    @ValueSource(strings = {"/api/orgs", "/actuator/health", "/api/auth/login", "/"})
    @Tag("AC-5.3")
    @DisplayName("AC-5.3 (more rows): TRACE with a token, a cookie and a custom header is a 405 D6 Problem echoing none of them")
    void traceEchoesNothing(String path) {
        RawHttp.Response response = RawHttp.send(port, "TRACE", path, "Authorization: " + TestTokens.bearerValid(),
                "Cookie: SESSION=qa-cookie-marker", "X-Qa-Marker: qa-header-marker");

        // Contract 0.3.1 (Problem.requestId): the TRACE 405 is the one gateway Problem without a request ID, because
        // Tomcat rejects TRACE before the gateway assigns one
        assertThat(response.status()).isEqualTo(405);
        assertThat(response.headerValues("Content-Type")).containsExactly("application/problem+json");
        assertThat(response.headerValues("X-Request-Id")).isEmpty();
        com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(response.body());
        assertThat(json.<java.util.Map<String, Object>>read("$").keySet())
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
        assertThat(json.read("$.type", String.class)).isEqualTo("about:blank");
        assertThat(json.read("$.title", String.class)).isEqualTo("Method Not Allowed");
        assertThat(json.read("$.status", Integer.class)).isEqualTo(405);
        assertThat(json.read("$.detail", String.class)).isEqualTo(METHOD_NOT_SUPPORTED);
        assertThat(json.read("$.instance", String.class)).isEqualTo(path);
        assertThat(response.body()).doesNotContain(TestTokens.valid(), "qa-cookie-marker", "qa-header-marker",
                "Authorization", "Cookie");
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "{0} /actuator/health")
    @ValueSource(strings = {"PUT", "DELETE", "PATCH"})
    @Tag("AC-5.2")
    @DisplayName("AC-5.2 (more rows): other methods /actuator/health doesn't support are 405 D6 Problems")
    void otherUnsupportedMethodsAreD6Problems(String method) {
        RawHttp.Response response = RawHttp.send(port, method, "/actuator/health",
                "Authorization: " + TestTokens.bearerValid());

        assertD6(response, 405, "Method Not Allowed", "/actuator/health", METHOD_NOT_SUPPORTED);
        assertNoStubReceivedARequest();
    }

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST,   /error,            valid token,   404",
            "DELETE, /error,            valid token,   404",
            "GET,    /error?status=500, valid token,   404",
            "GET,    /error,            expired token, 401",
            "POST,   /error,            no token,      401",
            "GET,    /error;x=1,        valid token,   400"})
    @Tag("AC-5.4")
    @DisplayName("AC-5.4 (more rows): a direct /error stays an unrouted path whatever the method, query or token")
    void directErrorVariants(String method, String target, String auth, int status) {
        RawHttp.Response response = switch (auth) {
            case "valid token" -> RawHttp.send(port, method, target, "Authorization: " + TestTokens.bearerValid());
            case "expired token" -> RawHttp.send(port, method, target, "Authorization: Bearer " + TestTokens.expired());
            default -> RawHttp.send(port, method, target);
        };

        String instance = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        switch (status) {
            case 404 -> assertD6(response, 404, "Not Found", instance, NO_ROUTE);
            case 401 -> {
                assertD6(response, 401, "Unauthorized", instance, TOKEN_REQUIRED);
                assertThat(response.headerValues("WWW-Authenticate")).containsExactly("Bearer");
            }
            default -> assertD6(response, 400, "Bad Request", instance, URL_REJECTED);
        }
        assertNoStubReceivedARequest();
    }

    @Test
    @Tag("AC-5.4")
    @DisplayName("AC-5.4: HEAD /error with a valid token is 404 with no body")
    void headErrorIs404WithoutBody() {
        RawHttp.Response response = RawHttp.send(port, "HEAD", "/error", "Authorization: " + TestTokens.bearerValid());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertNoStubReceivedARequest();
    }

    private static void assertD6(RawHttp.Response response, int status, String title, String instance, String detail) {
        String requestId = response.headerValues("X-Request-Id").stream().findFirst().orElse(null);
        String contentType = response.headerValues("Content-Type").stream().findFirst().orElse(null);
        assertProblem(response.status(), contentType, requestId, response.body(), status, title, instance);
        assertThat(com.jayway.jsonpath.JsonPath.parse(response.body()).read("$.detail", String.class)).isEqualTo(detail);
        assertThat(response.headerValues("X-Request-Id")).as("exactly one X-Request-Id").hasSize(1);
    }
}
