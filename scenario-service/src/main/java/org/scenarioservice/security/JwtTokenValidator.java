package org.scenarioservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.scenarioservice.configuration.JwtProperties;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates bearer tokens (D6), with auth-service's rules: signed with the shared secret, algorithm exactly HS512, and
 * {@code exp} present and in the future. Stricter than auth-service on purpose (FU-10): {@code sub} must be a
 * canonical UUID (8-4-4-4-12 hexadecimal digits, either case). The secret must be standard Base64 of at least 64
 * bytes; start-up fails otherwise, with a message that names the setting and never contains the value.
 */
@Component
public class JwtTokenValidator {

    static final int MINIMUM_SECRET_BYTES = 64;

    private static final String SECRET_SETTING = "app.jwtSecret";
    private static final String REQUIRED_ALGORITHM = Jwts.SIG.HS512.getId();
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final JwtParser parser;

    public JwtTokenValidator(JwtProperties properties) {
        this.parser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(decodeSecret(properties.jwtSecret()))).build();
    }

    /**
     * The caller's user id, or {@link InvalidTokenException} naming the rule the token breaks.
     */
    public UUID userIdOf(String token) {
        Jws<Claims> jws;
        try {
            jws = parser.parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException ex) {
            // jjwt's messages can quote header or claim values, so only the type is kept
            throw new InvalidTokenException("Token rejected: " + ex.getClass().getSimpleName());
        }
        if (!REQUIRED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("Token algorithm is not " + REQUIRED_ALGORITHM);
        }
        Claims claims = jws.getPayload();
        if (claims.getExpiration() == null) {
            throw new InvalidTokenException("Token has no exp claim");
        }
        String subject = claims.getSubject();
        if (subject == null || !CANONICAL_UUID.matcher(subject).matches()) {
            throw new InvalidTokenException("Token has no canonical UUID as sub claim");
        }
        return UUID.fromString(subject);
    }

    private static byte[] decodeSecret(String base64Secret) {
        if (base64Secret.contains("${")) {
            throw new IllegalStateException(
                    SECRET_SETTING + " must be set through the JWT_SECRET environment variable");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(SECRET_SETTING + " must be standard Base64 (RFC 4648 section 4)");
        }
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(SECRET_SETTING + " must decode to at least " + MINIMUM_SECRET_BYTES
                    + " bytes for HS512, but it decodes to " + secret.length + " bytes");
        }
        return secret;
    }
}
