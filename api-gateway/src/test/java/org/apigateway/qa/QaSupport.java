package org.apigateway.qa;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apigateway.support.TestTokens;
import org.springframework.boot.test.system.CapturedOutput;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * QA helpers shared by the {@code org.apigateway.qa} tests: extra adversarial tokens (minted with the same
 * TEST ONLY secret as {@link TestTokens}), the D6 Problem Details contract check, and access log lookups.
 */
final class QaSupport {

    static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._-]{1,128}$";
    static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    static final List<String> PROBLEM_FIELDS = List.of("type", "title", "status", "detail", "instance", "requestId");

    private static final SecretKey SHARED_KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TestTokens.SECRET));
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String ACCESS_LOGGER = "org.apigateway.access";

    private QaSupport() {
    }

    // ---- tokens ------------------------------------------------------------------------------------------

    /**
     * HS512 with the shared secret, exp in 1 hour, the given subject (null = no sub claim).
     */
    static String hs512WithSubject(String subject) {
        var builder = Jwts.builder().claim("email", TestTokens.EMAIL)
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plus(Duration.ofHours(1))));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.signWith(SHARED_KEY, Jwts.SIG.HS512).compact();
    }

    static String hs384WithSharedSecret() {
        return Jwts.builder().subject(TestTokens.USER_ID)
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(SHARED_KEY, Jwts.SIG.HS384).compact();
    }

    /**
     * Expired {@code age} ago (A3: no clock-skew allowance).
     */
    static String expiredAgo(Duration age) {
        Instant exp = Instant.now().minus(age);
        return Jwts.builder().subject(TestTokens.USER_ID)
                .issuedAt(Date.from(exp.minus(Duration.ofHours(1)))).expiration(Date.from(exp))
                .signWith(SHARED_KEY, Jwts.SIG.HS512).compact();
    }

    /**
     * A token with an arbitrary JOSE header and claims, and the given (possibly empty) signature part.
     */
    static String handMade(String headerJson, String claimsJson, String signature) {
        return BASE64_URL.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + BASE64_URL.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + "." + signature;
    }

    static String validClaimsJson() {
        long now = Instant.now().getEpochSecond();
        return "{\"sub\":\"" + TestTokens.USER_ID + "\",\"email\":\"" + TestTokens.EMAIL
                + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}";
    }

    /**
     * A correctly signed HS512 token (shared secret) for hand-written claims, so claims can have types jjwt's builder
     * would refuse, for example a numeric or array {@code sub}.
     */
    static String hs512Signed(String claimsJson) {
        String signingInput = BASE64_URL.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + BASE64_URL.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(TestTokens.SECRET), "HmacSHA512"));
            return signingInput + "." + BASE64_URL.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA512 is part of every JDK", ex);
        }
    }

    /**
     * Claims JSON with exp 1 hour from now and the given raw JSON value for {@code sub}.
     */
    static String claimsWithRawSubject(String subJsonValue) {
        long now = Instant.now().getEpochSecond();
        return "{\"sub\":" + subJsonValue + ",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}";
    }

    /**
     * The valid token's header and payload with its signature removed (the last dot kept).
     */
    static String signatureStripped() {
        String[] parts = TestTokens.valid().split("\\.");
        return parts[0] + "." + parts[1] + ".";
    }

    /**
     * An expired token whose payload was edited to expire in the future; the original signature is kept.
     */
    static String expiredWithExtendedExp() {
        String[] parts = TestTokens.expired().split("\\.");
        String payload = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        long future = Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
        String extended = payload.replaceAll("\"exp\":\\d+", "\"exp\":" + future);
        return parts[0] + "." + BASE64_URL.encodeToString(extended.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    /**
     * Header and payload of one valid token with the signature of another valid token for a different subject.
     */
    static String splicedSignature() {
        String[] victim = TestTokens.valid().split("\\.");
        String[] other = hs512WithSubject("qa-other-user").split("\\.");
        return other[0] + "." + other[1] + "." + victim[2];
    }

    // ---- Problem Details (D6, contract components.schemas.Problem) ----------------------------------------

    /**
     * Status, content type, the exact field set, field values and requestId == X-Request-Id.
     */
    static void assertProblem(int actualStatus, String contentType, String requestIdHeader, String body,
                              int status, String title, String instance) {
        assertThat(actualStatus).as("status").isEqualTo(status);
        assertThat(contentType).as("Content-Type").isEqualTo("application/problem+json");
        DocumentContext json = JsonPath.parse(body);
        Map<String, Object> fields = json.read("$");
        assertThat(fields.keySet()).as("Problem fields (no extras such as timestamp, error, path or trace)")
                .containsExactlyInAnyOrderElementsOf(PROBLEM_FIELDS);
        assertThat(json.read("$.type", String.class)).isEqualTo("about:blank");
        assertThat(json.read("$.status", Integer.class)).isEqualTo(status);
        assertThat(json.read("$.title", String.class)).isEqualTo(title);
        assertThat(json.read("$.instance", String.class)).isEqualTo(instance);
        assertThat(json.read("$.detail", String.class)).isNotBlank();
        assertThat(requestIdHeader).as("X-Request-Id header").isNotNull().matches(REQUEST_ID_PATTERN);
        assertThat(json.read("$.requestId", String.class)).isEqualTo(requestIdHeader);
    }

    // ---- access log (D7) ---------------------------------------------------------------------------------

    /**
     * Waits until the access entry for {@code requestId} is written, then checks it is the only one.
     */
    static String onlyAccessEntry(CapturedOutput output, String requestId) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !accessEntries(output, requestId).isEmpty());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> accessEntries(output, requestId).size() == 1);
        return accessEntries(output, requestId).get(0) + " ";
    }

    static List<String> accessEntries(CapturedOutput output, String requestId) {
        Pattern entry = Pattern.compile(Pattern.quote(ACCESS_LOGGER) + ".*requestId=" + Pattern.quote(requestId) + "(\\s|$)");
        return output.getAll().lines().filter(line -> entry.matcher(line).find()).toList();
    }
}
