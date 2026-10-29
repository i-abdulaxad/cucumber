package org.apigateway.support;

import org.apigateway.ApiGatewayApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts the gateway, or resolves its configuration, with a controlled set of environment variables
 * instead of the real process environment. Used for the operator settings in D3 (ports, secret, CORS).
 */
public final class GatewayLauncher {

    /**
     * Every test server listens on, and every port is checked on, {@code 127.0.0.1} only (platform-hardening T8,
     * RC-2): on macOS a wildcard listener can share its port with another process's {@code 127.0.0.1} listener, which
     * then receives the test's calls. A port bound or checked on {@code 127.0.0.1} can't be in use there.
     */
    public static final String LOOPBACK = "127.0.0.1";

    private GatewayLauncher() {
    }

    /** Environment variables every launched gateway needs, unless a test replaces them. */
    public static Map<String, Object> testEnvironment() {
        return new HashMap<>(Map.of(
                "SERVER_PORT", "0",
                "SERVER_ADDRESS", LOOPBACK,
                "JWT_SECRET", TestTokens.SECRET));
    }

    /** Starts the gateway with exactly these environment variables; the caller closes the context. */
    public static ConfigurableApplicationContext run(Map<String, Object> environmentVariables, String... profiles) {
        return new SpringApplicationBuilder(ApiGatewayApplication.class)
                .environment(environmentWith(environmentVariables))
                .profiles(profiles)
                .registerShutdownHook(false)
                .run();
    }

    /** Resolves the gateway's configuration files against these environment variables without starting it. */
    public static ConfigurableEnvironment resolveConfiguration(Map<String, Object> environmentVariables) {
        ConfigurableEnvironment environment = environmentWith(environmentVariables);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static ConfigurableEnvironment environmentWith(Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.copyOf(environmentVariables)));
        return environment;
    }
}
