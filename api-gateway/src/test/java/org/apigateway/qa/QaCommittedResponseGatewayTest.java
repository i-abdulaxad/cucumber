package org.apigateway.qa;

import org.apigateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared setup for the platform-hardening US-6 tests (D11, gateway FU-4 abort, BUG-api-gateway-14): scenario-service
 * is a raw socket stub and the gateway's response timeout is 2 s. Every US-6 class extends this one, so they share a
 * single Spring context. The raw stub is deliberately not closed after a class: the cached context keeps pointing at
 * it, and its threads are daemons.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "GATEWAY_RESPONSE_TIMEOUT=2s")
abstract class QaCommittedResponseGatewayTest extends GatewayIntegrationTest {

    static final RawStubServer RAW_SCENARIO_SERVICE = RawStubServer.start();

    /** The AC-4.7 detail of the 502 Problem (gateway contract, components.responses.BadGateway). */
    static final String BAD_RESPONSE_DETAIL = "The service handling this request returned an invalid response.";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void scenarioServiceIsARawSocketStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.downstream.scenario-service-url", RAW_SCENARIO_SERVICE::baseUrl);
    }

    @BeforeEach
    void resetRawScenarioService() {
        RAW_SCENARIO_SERVICE.reset();
    }
}
