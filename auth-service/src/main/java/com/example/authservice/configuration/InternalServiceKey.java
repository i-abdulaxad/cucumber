package com.example.authservice.configuration;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * The checked service key (report-service spec D5). Unset or empty disables {@code /internal/service/**}: nothing
 * matches, so every request there is 401. When set, it must be standard Base64 of at least {@value #MINIMUM_BYTES}
 * bytes without {@code ${}; otherwise start-up fails with a message that names {@code INTERNAL_SERVICE_KEY} and never
 * the value. A presented key is compared in constant time: both sides are hashed with SHA-256 first, so the
 * comparison takes the same time whatever the presented key's length or content.
 */
@Component
public class InternalServiceKey {

    static final int MINIMUM_BYTES = 32;

    private static final String SETTING = "app.internal-service-key (INTERNAL_SERVICE_KEY)";

    private final Optional<byte[]> keyHash;

    public InternalServiceKey(InternalServiceProperties properties) {
        String key = properties.internalServiceKey();
        this.keyHash = key == null || key.isEmpty() ? Optional.empty() : Optional.of(sha256(checked(key)));
    }

    /** Whether the key is configured; when it isn't, no request is a service. */
    public boolean enabled() {
        return keyHash.isPresent();
    }

    /** Whether the presented header value is exactly the configured key, compared in constant time. */
    public boolean matches(String presented) {
        if (presented == null || keyHash.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented), keyHash.get());
    }

    @Override
    public String toString() {
        return "InternalServiceKey[enabled=" + enabled() + "]";
    }

    private static String checked(String key) {
        if (key.contains("${")) {
            throw new IllegalStateException(SETTING + " must be set through the INTERNAL_SERVICE_KEY environment "
                    + "variable, without \"${\"");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(SETTING + " must be standard Base64 (RFC 4648 section 4)");
        }
        if (decoded.length < MINIMUM_BYTES) {
            throw new IllegalStateException(SETTING + " must decode to at least " + MINIMUM_BYTES
                    + " bytes, but it decodes to " + decoded.length + " bytes");
        }
        return key;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
