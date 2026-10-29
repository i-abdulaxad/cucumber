package org.reportservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.reportservice.configuration.JwtSecretCheck;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates user JWTs (D5, contract C2), with scenario-service's rules: signed with the shared secret (checked at
 * start-up by {@link JwtSecretCheck}), algorithm exactly HS512, and {@code exp} present and in the future. {@code sub}
 * must be a canonical UUID in lower case, as auth-service issues it (AC-6.4).
 */
@Component
public class JwtTokenValidator {

    private static final String REQUIRED_ALGORITHM = Jwts.SIG.HS512.getId();
    private static final Pattern LOWER_CASE_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final JwtParser parser;

    public JwtTokenValidator(JwtSecretCheck secret) {
        this.parser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.secret())).build();
    }

    /** The caller's user id, or {@link InvalidTokenException} naming the rule the token breaks. */
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
        if (subject == null || !LOWER_CASE_UUID.matcher(subject).matches()) {
            throw new InvalidTokenException("Token has no lower-case canonical UUID as sub claim");
        }
        return UUID.fromString(subject);
    }
}
