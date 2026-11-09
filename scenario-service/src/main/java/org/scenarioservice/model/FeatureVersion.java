package org.scenarioservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable version of a feature's text (ERD {@code feature_versions}); never updated or deleted (D9).
 */
@Entity
@Table(name = "feature_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeatureVersion extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID featureId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "text")
    private String source;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Without source or message (P16).
     */
    @Override
    public String toString() {
        return "FeatureVersion[id=" + id + ", featureId=" + featureId + ", version=" + version + "]";
    }
}
