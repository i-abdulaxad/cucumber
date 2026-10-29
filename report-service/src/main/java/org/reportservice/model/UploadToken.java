package org.reportservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A CI upload token of a project (ERD {@code upload_tokens}, D4): only the SHA-256 hex of the token is stored, never
 * the token. No {@code toString} shows the hash or the prefix.
 */
@Entity
@Table(name = "upload_tokens")
@Getter
@Setter
@NoArgsConstructor
public class UploadToken extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 16)
    private String tokenPrefix;

    @Column(nullable = false)
    private UUID createdBy;

    private Instant expiresAt;

    private Instant lastUsedAt;

    private Instant revokedAt;

    private UUID revokedBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
