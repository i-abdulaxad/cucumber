package com.example.authservice.support;

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
 * The TEST-ONLY JWT secret and a factory for tokens the API can't produce (D10): expired, wrongly signed,
 * unsigned, altered, or with an unusual subject.
 */
public final class TestJwt {

    /**
     * TEST ONLY shared secret: standard Base64 of the 77-byte ASCII text
     * "auth-service TEST ONLY hs512 signing secret; never used in a real environment". Never used outside tests.
     */
    public static final String SECRET =
            "YXV0aC1zZXJ2aWNlIFRFU1QgT05MWSBoczUxMiBzaWduaW5nIHNlY3JldDsgbmV2ZXIgdXNlZCBpbiBhIHJlYWwgZW52aXJvbm1lbnQ=";

    private static final SecretKey KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
    private static final SecretKey OTHER_KEY = Keys.hmacShaKeyFor(
            "a different TEST ONLY secret of at least sixty-four bytes for HS512 signing"
                    .getBytes(StandardCharsets.US_ASCII));
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private TestJwt() {
    }

    /**
     * HS512 with the shared secret, the given {@code sub} and email, exp 1 hour from now.
     */
    public static String valid(UUID userId, String email) {
        return withSubject(userId.toString(), email);
    }

    /**
     * HS512 with the shared secret and the given {@code sub}, exp 1 hour from now.
     */
    public static String withSubject(String subject, String email) {
        return claims(email, Instant.now().plus(ONE_HOUR)).subject(subject)
                .signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String withoutSubject(String email) {
        return claims(email, Instant.now().plus(ONE_HOUR)).signWith(KEY, Jwts.SIG.HS512).compact();
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

    /**
     * The claims of a valid token with header {@code alg: none} and no signature.
     */
    public static String unsigned(UUID userId, String email) {
        String[] parts = valid(userId, email).split("\\.");
        String header = BASE64_URL.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + parts[1] + ".";
    }

    /**
     * A valid token whose {@code sub} was replaced after signing; the original signature is kept.
     */
    public static String withAlteredSubject(UUID userId, String email, UUID newSubject) {
        String[] parts = valid(userId, email).split("\\.");
        String payload = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        String altered = payload.replace(userId.toString(), newSubject.toString());
        return parts[0] + "." + BASE64_URL.encodeToString(altered.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    /**
     * The shared signing key, for tests that verify issued tokens.
     */
    public static SecretKey key() {
        return KEY;
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
