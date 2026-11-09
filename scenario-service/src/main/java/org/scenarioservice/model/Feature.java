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
 * One Gherkin file of a project (ERD {@code features}). {@code projectId} references auth-service by id only.
 */
@Entity
@Table(name = "features")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Feature extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(nullable = false)
    private int currentVersion;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private UUID updatedBy;

    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Ids and version only: path, name and description are user content (P16).
     */
    @Override
    public String toString() {
        return "Feature[id=" + id + ", projectId=" + projectId + ", currentVersion=" + currentVersion + "]";
    }
}
