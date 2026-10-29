package org.reportservice.service.impl;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The one place that knows the upload token format (D4): {@code rct_} plus the base64url (no padding) of 32
 * {@link SecureRandom} bytes, 47 characters; its lower-case SHA-256 hex, the only thing stored; and its 12-character
 * prefix. The upload's token authenticator (T7) hashes presented tokens with the same {@link #hash}. Nothing here logs.
 */
@Component
public class UploadTokenCodec {

    static final String PREFIX = "rct_";
    static final int RANDOM_BYTES = 32;
    static final int SHOWN_PREFIX_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    /** A new token text. */
    public String newToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The lower-case SHA-256 hex of the full token text. */
    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The first 12 characters, shown in lists. */
    public String prefix(String token) {
        return token.substring(0, SHOWN_PREFIX_LENGTH);
    }
}
