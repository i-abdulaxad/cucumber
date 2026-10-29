package org.reportservice.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Tokens for tests (spec glossary "{token of X}", a copy of scenario-service's): HS512 with the TEST-ONLY secret
 * ({@link TestSettings#JWT_SECRET}), {@code exp} 1 hour after issue, {@code sub} the user id and {@code email} the
 * user's email; and the broken tokens of AC-6.4.
 */
public final class TestTokens {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TestSettings.JWT_SECRET));
    private static final SecretKey OTHER_KEY = Keys.hmacShaKeyFor(
            "a different TEST ONLY secret of at least sixty-four bytes for HS512 signing"
                    .getBytes(StandardCharsets.US_ASCII));
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private TestTokens() {
    }

    /** {@code {token of X}}. */
    public static String valid(UUID userId, String email) {
        return withSubject(userId.toString(), email);
    }

    public static String withSubject(String subject, String email) {
        return claims(email, Instant.now().plus(ONE_HOUR)).subject(subject).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String expired(UUID userId, String email) {
        return claims(email, Instant.now().minus(ONE_HOUR)).subject(userId.toString())
                .signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String signedWithAnotherSecret(UUID userId, String email) {
        return claims(email, Instant.now().plus(ONE_HOUR)).subject(userId.toString())
                .signWith(OTHER_KEY, Jwts.SIG.HS512).compact();
    }

    public static String signedWithHs256(UUID userId, String email) {
        return claims(email, Instant.now().plus(ONE_HOUR)).subject(userId.toString())
                .signWith(KEY, Jwts.SIG.HS256).compact();
    }

    public static String withoutExpiration(UUID userId, String email) {
        return Jwts.builder().claim("email", email).subject(userId.toString()).issuedAt(new Date())
                .signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String withoutSubject(String email) {
        return claims(email, Instant.now().plus(ONE_HOUR)).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    /** The claims of a valid token with header {@code alg: none} and no signature. */
    public static String unsigned(UUID userId, String email) {
        String[] parts = valid(userId, email).split("\\.");
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + parts[1] + ".";
    }

    private static JwtBuilder claims(String email, Instant expiration) {
        return Jwts.builder()
                .claim("email", email)
                .claim("orgs", List.of())
                .claim("mfa", false)
                .issuedAt(Date.from(expiration.minus(ONE_HOUR)))
                .expiration(Date.from(expiration));
    }
}
