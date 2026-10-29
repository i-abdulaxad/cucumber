package org.apigateway.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Common setup for tests that run the real gateway on an ephemeral port against stubbed services (D8).
 * The stubs are shared by every subclass so that test classes with the same settings share one
 * Spring context. Each test starts with fresh stubs that answer status 200 with body {@code {}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jwt.secret=" + TestTokens.SECRET, "server.address=" + GatewayLauncher.LOOPBACK})
public abstract class GatewayIntegrationTest {

    protected static final StubServer AUTH_SERVICE = StubServer.start("auth-service");
    protected static final StubServer SCENARIO_SERVICE = StubServer.start("scenario-service");
    protected static final StubServer REPORT_SERVICE = StubServer.start("report-service");

    private static final Map<String, StubServer> STUBS_BY_SERVICE = Map.of(
            AUTH_SERVICE.name(), AUTH_SERVICE,
            SCENARIO_SERVICE.name(), SCENARIO_SERVICE,
            REPORT_SERVICE.name(), REPORT_SERVICE);

    @LocalServerPort
    private int port;

    protected GatewayClient gateway;

    @DynamicPropertySource
    static void downstreamServiceUrls(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", AUTH_SERVICE::baseUrl);
        registry.add("SCENARIO_SERVICE_URL", SCENARIO_SERVICE::baseUrl);
        registry.add("REPORT_SERVICE_URL", REPORT_SERVICE::baseUrl);
    }

    @BeforeEach
    void resetStubsAndClient() {
        STUBS_BY_SERVICE.values().forEach(StubServer::reset);
        gateway = new GatewayClient(port);
    }

    protected static StubServer stub(String service) {
        StubServer stub = STUBS_BY_SERVICE.get(service);
        assertThat(stub).as("stub for %s", service).isNotNull();
        return stub;
    }

    protected static List<StubServer> stubsOtherThan(StubServer stub) {
        return STUBS_BY_SERVICE.values().stream().filter(other -> other != stub).toList();
    }

    protected static void assertNoStubReceivedARequest() {
        STUBS_BY_SERVICE.values().forEach(stub ->
                assertThat(stub.requests()).as("requests received by %s", stub.name()).isEmpty());
    }
}
