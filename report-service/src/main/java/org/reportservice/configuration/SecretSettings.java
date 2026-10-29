package org.reportservice.configuration;

import java.util.Base64;

/**
 * The format rule shared by the service's Base64 secrets (D15): set (not blank, no {@code ${}), standard Base64
 * (RFC 4648 section 4), and at least a minimum number of bytes. A failure names the property and its environment
 * variable, and never contains the value.
 */
final class SecretSettings {

    private SecretSettings() {
    }

    /** The decoded secret, or {@link IllegalStateException} naming the setting. */
    static byte[] decode(String value, String property, String variable, int minimumBytes) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException(property + " must be set through the " + variable
                    + " environment variable");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(property + " (" + variable
                    + ") must be standard Base64 (RFC 4648 section 4)");
        }
        if (decoded.length < minimumBytes) {
            throw new IllegalStateException(property + " (" + variable + ") must decode to at least " + minimumBytes
                    + " bytes, but it decodes to " + decoded.length + " bytes");
        }
        return decoded;
    }
}
