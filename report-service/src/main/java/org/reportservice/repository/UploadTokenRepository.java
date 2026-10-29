package org.reportservice.repository;

import org.reportservice.model.UploadToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Upload tokens (D4). Tokens are matched by hash only, never by their text. */
public interface UploadTokenRepository extends JpaRepository<UploadToken, UUID> {

    Optional<UploadToken> findByIdAndProjectId(UUID id, UUID projectId);

    /** The project's tokens, newest first ({@code createdAt}, then {@code id}, both descending; AC-1.2). */
    List<UploadToken> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

    /** Active tokens: not revoked, and no expiry or one after {@code now} (D4). */
    @Query("SELECT count(t) FROM UploadToken t WHERE t.projectId = :projectId AND t.revokedAt IS NULL "
            + "AND (t.expiresAt IS NULL OR t.expiresAt > :now)")
    long countActive(@Param("projectId") UUID projectId, @Param("now") Instant now);
}
