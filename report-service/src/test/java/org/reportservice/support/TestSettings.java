package org.reportservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * TEST-ONLY settings of the shared test context (D15, D16): the JWT secret, the internal service key, and the address
 * of the auth-service stub ({@link AccessCheckStub}). The stub's port is reserved once per test JVM on
 * {@code 127.0.0.1}: a server socket holds it until the stub {@link #releaseAuthServicePort() releases} it and binds
 * it itself, so every test context points at the same address. Never used outside tests, and never a real value.
 */
public final class TestSettings {

    /**
     * TEST ONLY shared secret: standard Base64 of the 79-byte ASCII text
     * "report-service TEST ONLY hs512 signing secret; never used in a real environment".
     */
    public static final String JWT_SECRET = "cmVwb3J0LXNlcnZpY2UgVEVTVCBPTkxZIGhzNTEyIHNpZ25pbmcgc2VjcmV0OyBuZXZl"
            + "ciB1c2VkIGluIGEgcmVhbCBlbnZpcm9ubWVudA==";

    /**
     * TEST ONLY service key: standard Base64 of the 48-byte ASCII text
     * "report-service TEST ONLY service key; never real".
     */
    public static final String INTERNAL_SERVICE_KEY =
            "cmVwb3J0LXNlcnZpY2UgVEVTVCBPTkxZIHNlcnZpY2Uga2V5OyBuZXZlciByZWFs";

    private static final ServerSocket RESERVATION = reserve();

    /** The auth-service stub's port, the same for the whole test JVM. */
    public static final int AUTH_SERVICE_PORT = RESERVATION.getLocalPort();

    private TestSettings() {
    }

    /** {@code http://127.0.0.1:<the reserved port>}, published as {@code app.auth-service.base-url}. */
    public static String authServiceBaseUrl() {
        return "http://127.0.0.1:" + AUTH_SERVICE_PORT;
    }

    /** Frees the reserved port so that the stub can bind it; idempotent. Only the stub calls this, right before. */
    static synchronized int releaseAuthServicePort() {
        try {
            RESERVATION.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return AUTH_SERVICE_PORT;
    }

    @DynamicPropertySource
    static void testSettings(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("INTERNAL_SERVICE_KEY", () -> INTERNAL_SERVICE_KEY);
        registry.add("app.auth-service.base-url", TestSettings::authServiceBaseUrl);
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
