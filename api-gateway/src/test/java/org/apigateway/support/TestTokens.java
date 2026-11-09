package org.apigateway.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Mints the tokens used in the spec's scenarios with a TEST-ONLY secret (D8).
 */
public final class TestTokens {

    /**
     * TEST ONLY shared secret: standard Base64 of the ASCII text
     * "api-gateway TEST ONLY hs512 signing secret; never used in a real environment" (76 bytes).
     */
    public static final String SECRET =
            "YXBpLWdhdGV3YXkgVEVTVCBPTkxZIGhzNTEyIHNpZ25pbmcgc2VjcmV0OyBuZXZlciB1c2VkIGluIGEgcmVhbCBlbnZpcm9ubWVudA==";

    /**
     * The {@code sub} of {valid token} in the spec.
     */
    public static final String USER_ID = "7f1c2a9e-0000-4000-8000-000000000001";
    public static final String EMAIL = "ana@example.com";
    public static final String FORGED_USER_ID = "00000000-0000-0000-0000-000000000666";

    private static final SecretKey KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
    private static final SecretKey OTHER_KEY = Keys.hmacShaKeyFor(
            "a different TEST ONLY secret of at least sixty-four bytes for HS512 signing"
                    .getBytes(StandardCharsets.US_ASCII));
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private TestTokens() {
    }

    /**
     * {valid token}: HS512 with the shared secret, sub = {@link #USER_ID}, exp 1 hour after issue.
     */
    public static String valid() {
        return claims(Instant.now().plus(ONE_HOUR)).subject(USER_ID).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String bearerValid() {
        return "Bearer " + valid();
    }

    public static String expired() {
        return claims(Instant.now().minus(ONE_HOUR)).subject(USER_ID).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String signedWithAnotherSecret() {
        return claims(Instant.now().plus(ONE_HOUR)).subject(USER_ID).signWith(OTHER_KEY, Jwts.SIG.HS512).compact();
    }

    public static String signedWithHs256() {
        return claims(Instant.now().plus(ONE_HOUR)).subject(USER_ID).signWith(KEY, Jwts.SIG.HS256).compact();
    }

    /**
     * HS512 with the shared secret, exp 1 hour after issue, and the given {@code sub}.
     */
    public static String withSubject(String subject) {
        return claims(Instant.now().plus(ONE_HOUR)).subject(subject).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String withoutSubject() {
        return claims(Instant.now().plus(ONE_HOUR)).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String withoutExpiration() {
        return Jwts.builder().subject(USER_ID).claim("email", EMAIL).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    /**
     * The valid token's claims with header {@code alg: none} and no signature.
     */
    public static String unsigned() {
        String[] parts = valid().split("\\.");
        String header = BASE64_URL.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + parts[1] + ".";
    }

    /**
     * A valid token whose payload's sub was changed after signing; the original signature is kept.
     */
    public static String withAlteredSubject(String subject) {
        String[] parts = valid().split("\\.");
        String payload = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        String altered = payload.replace(USER_ID, subject);
        return parts[0] + "." + BASE64_URL.encodeToString(altered.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    private static JwtBuilder claims(Instant expiration) {
        Instant issuedAt = expiration.minus(ONE_HOUR);
        return Jwts.builder()
                .claim("email", EMAIL)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration));
    }
}
