package org.reportservice.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Test data in the shared test database (database pattern item 15): {@link #reset} empties report-service's tables,
 * children first; {@link #token} stores an upload token row directly, as D4 describes it (only the hash).
 */
public final class ReportData {

    private ReportData() {
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM step_results");
        jdbc.update("DELETE FROM test_case_results");
        jdbc.update("DELETE FROM test_runs");
        jdbc.update("DELETE FROM upload_tokens");
        jdbc.update("DELETE FROM builds");
        jdbc.update("DELETE FROM environments");
    }

    /** Stores a token of the project with the given state and returns its id; the token text is {@code token}. */
    public static UUID token(JdbcTemplate jdbc, UUID projectId, String name, String token, UUID createdBy,
                             Instant createdAt, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO upload_tokens (id, project_id, name, token_hash, token_prefix, created_by, "
                        + "expires_at, revoked_at, revoked_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, projectId, name, sha256Hex(token), token.substring(0, 12), createdBy, timestamp(expiresAt),
                timestamp(revokedAt), revokedAt == null ? null : createdBy, timestamp(createdAt),
                timestamp(createdAt));
        return id;
    }

    /** The lower-case SHA-256 hex of the text, as {@code upload_tokens.token_hash} holds it (D4). */
    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A random token in D4's format (for rows stored directly). */
    public static String randomToken() {
        String hex = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return "rct_" + hex.substring(0, 43);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
