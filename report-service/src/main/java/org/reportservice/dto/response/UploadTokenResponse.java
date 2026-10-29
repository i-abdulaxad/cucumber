package org.reportservice.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * An upload token without its secret (D4, AC-1.2): never the token or its hash. {@link #toString()} leaves out the
 * name and the prefix, so framework logging of the response can't show them (AC-1.10).
 */
public record UploadTokenResponse(UUID id, String name, String tokenPrefix, UUID createdBy, Instant createdAt,
                                  Instant expiresAt, Instant lastUsedAt, Instant revokedAt, UUID revokedBy) {

    @Override
    public String toString() {
        return "UploadTokenResponse[id=" + id + "]";
    }
}
