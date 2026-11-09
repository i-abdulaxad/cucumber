package org.scenarioservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Test-only settings of the shared test context (D15): the TEST-ONLY JWT secret, and the address of the access check
 * stub. The stub's port is reserved once per test JVM: a server socket holds it until the stub
 * {@link #releaseAccessCheckPort() releases} it and binds it itself, so no other process can take it in between, and
 * every test context points at the same address (P18; pm decision 2026-10-18, Q4).
 */
public final class TestSettings {

    /**
     * TEST ONLY shared secret: standard Base64 of the 81-byte ASCII text
     * "scenario-service TEST ONLY hs512 signing secret; never used in a real environment". Never used outside tests.
     */
    public static final String JWT_SECRET = "c2NlbmFyaW8tc2VydmljZSBURVNUIE9OTFkgaHM1MTIgc2lnbmluZyBzZWNyZXQ7IG5ldmVy"
            + "IHVzZWQgaW4gYSByZWFsIGVudmlyb25tZW50";

    private static final ServerSocket RESERVATION = reserve();

    /**
     * The access check stub's port, the same for the whole test JVM.
     */
    public static final int ACCESS_CHECK_PORT = RESERVATION.getLocalPort();

    private TestSettings() {
    }

    /**
     * {@code http://localhost:<the reserved port>}, published as {@code app.auth-service.base-url}.
     */
    public static String accessCheckBaseUrl() {
        return "http://localhost:" + ACCESS_CHECK_PORT;
    }

    /**
     * Frees the reserved port so that the access check stub can bind it; idempotent. Only the stub calls this, right
     * before it binds.
     */
    public static synchronized int releaseAccessCheckPort() {
        try {
            RESERVATION.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ACCESS_CHECK_PORT;
    }

    @DynamicPropertySource
    static void testSettings(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("app.auth-service.base-url", TestSettings::accessCheckBaseUrl);
    }

    private static ServerSocket reserve() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
