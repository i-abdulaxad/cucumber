package org.reportservice.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * The created token (D4, AC-1.1): the only answer that carries {@code token}. {@link #toString()} shows neither the
 * token nor its prefix, so framework logging of the response can't show them (AC-1.10).
 */
public record CreatedUploadTokenResponse(UUID id, String name, String token, String tokenPrefix, UUID createdBy,
                                         Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt,
                                         UUID revokedBy) {

    @Override
    public String toString() {
        return "CreatedUploadTokenResponse[id=" + id + ", token=<redacted>]";
    }
}
